#!/usr/bin/env python3
"""
EC2 Manager Lambda - Auto-start/stop Orpheus TTS GPU instance for cost optimization.

Actions:
  - start: Start instance, wait for running, get public IP, update SSM, poll health endpoint
  - stop: Stop instance
  - idle_check: Check SQS queue depth; if empty for 15 min, stop instance

Triggers:
  - CloudWatch Alarm (queue depth > 0) -> start
  - EventBridge scheduled rule (every 5 min) -> idle_check
  - Manual invocation with {"action": "start"|"stop"}
"""

import json
import os
import time
import logging
import boto3
import urllib.request
import urllib.error

logger = logging.getLogger()
logger.setLevel(logging.INFO)

ec2_client = boto3.client('ec2')
ssm_client = boto3.client('ssm')
sqs_client = boto3.client('sqs')

INSTANCE_ID = os.environ.get('EC2_INSTANCE_ID', 'i-0ed07110e0cc39f9a')
SSM_PARAM_NAME = os.environ.get('SSM_PARAM_NAME', '/microservice-tts/orpheus-api-url')
TTS_QUEUE_URL = os.environ.get('TTS_QUEUE_URL', '')
IDLE_TIMEOUT_MINUTES = int(os.environ.get('IDLE_TIMEOUT_MINUTES', '15'))
SSM_LAST_ACTIVITY_PARAM = os.environ.get('SSM_LAST_ACTIVITY_PARAM', '/microservice-tts/orpheus-last-activity')
HEALTH_CHECK_TIMEOUT = int(os.environ.get('HEALTH_CHECK_TIMEOUT', '180'))  # seconds
HEALTH_CHECK_INTERVAL = 10  # seconds between polls
ORPHEUS_API_PORT = 8000


def get_instance_state():
    """Get current EC2 instance state."""
    resp = ec2_client.describe_instances(InstanceIds=[INSTANCE_ID])
    state = resp['Reservations'][0]['Instances'][0]['State']['Name']
    return state


def get_instance_public_ip():
    """Get public IP of the instance (only available when running)."""
    resp = ec2_client.describe_instances(InstanceIds=[INSTANCE_ID])
    instance = resp['Reservations'][0]['Instances'][0]
    return instance.get('PublicIpAddress')


def update_ssm_orpheus_url(public_ip):
    """Store the current Orpheus API URL in SSM Parameter Store."""
    url = f"http://{public_ip}:{ORPHEUS_API_PORT}"
    ssm_client.put_parameter(
        Name=SSM_PARAM_NAME,
        Value=url,
        Type='String',
        Overwrite=True,
    )
    logger.info(f"Updated SSM {SSM_PARAM_NAME} = {url}")
    return url


def update_last_activity_timestamp():
    """Record current time as last activity in SSM."""
    import math
    timestamp = str(int(time.time()))
    ssm_client.put_parameter(
        Name=SSM_LAST_ACTIVITY_PARAM,
        Value=timestamp,
        Type='String',
        Overwrite=True,
    )
    logger.info(f"Updated last activity timestamp: {timestamp}")


def get_last_activity_timestamp():
    """Get the last activity timestamp from SSM. Returns 0 if not set."""
    try:
        resp = ssm_client.get_parameter(Name=SSM_LAST_ACTIVITY_PARAM)
        return int(resp['Parameter']['Value'])
    except ssm_client.exceptions.ParameterNotFound:
        return 0


def wait_for_instance_running(timeout=120):
    """Wait for EC2 instance to reach 'running' state."""
    logger.info(f"Waiting for instance {INSTANCE_ID} to reach 'running' state...")
    waiter = ec2_client.get_waiter('instance_running')
    waiter.wait(
        InstanceIds=[INSTANCE_ID],
        WaiterConfig={'Delay': 5, 'MaxAttempts': timeout // 5},
    )
    logger.info("Instance is running.")


def poll_health_endpoint(public_ip):
    """Poll the Orpheus /health endpoint until it returns OK or timeout."""
    url = f"http://{public_ip}:{ORPHEUS_API_PORT}/health"
    logger.info(f"Polling health endpoint: {url} (timeout: {HEALTH_CHECK_TIMEOUT}s)")

    deadline = time.time() + HEALTH_CHECK_TIMEOUT
    last_error = None

    while time.time() < deadline:
        try:
            req = urllib.request.Request(url, method='GET')
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    body = resp.read().decode('utf-8')
                    logger.info(f"Health check passed: {body}")
                    return True
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            last_error = str(e)
            logger.info(f"Health check not ready: {last_error}")

        time.sleep(HEALTH_CHECK_INTERVAL)

    logger.warning(f"Health check timed out after {HEALTH_CHECK_TIMEOUT}s. Last error: {last_error}")
    return False


def get_queue_depth():
    """Get approximate number of messages visible in the TTS queue."""
    if not TTS_QUEUE_URL:
        logger.warning("TTS_QUEUE_URL not configured")
        return 0
    resp = sqs_client.get_queue_attributes(
        QueueUrl=TTS_QUEUE_URL,
        AttributeNames=['ApproximateNumberOfMessagesVisible', 'ApproximateNumberOfMessagesNotVisible'],
    )
    visible = int(resp['Attributes'].get('ApproximateNumberOfMessagesVisible', '0'))
    in_flight = int(resp['Attributes'].get('ApproximateNumberOfMessagesNotVisible', '0'))
    total = visible + in_flight
    logger.info(f"Queue depth: {visible} visible, {in_flight} in-flight, {total} total")
    return total


def handle_start():
    """Start the EC2 instance, wait for it, update SSM with new IP, poll health."""
    state = get_instance_state()

    if state == 'running':
        public_ip = get_instance_public_ip()
        if public_ip:
            url = update_ssm_orpheus_url(public_ip)
            update_last_activity_timestamp()
            return {
                'action': 'start',
                'status': 'already_running',
                'public_ip': public_ip,
                'orpheus_url': url,
            }
        logger.warning("Instance running but no public IP yet, waiting...")

    if state == 'stopped':
        logger.info(f"Starting instance {INSTANCE_ID}")
        ec2_client.start_instances(InstanceIds=[INSTANCE_ID])
    elif state in ('pending', 'running'):
        logger.info(f"Instance is {state}, waiting for running...")
    elif state == 'stopping':
        logger.info("Instance is stopping, waiting for stopped then starting...")
        waiter = ec2_client.get_waiter('instance_stopped')
        waiter.wait(InstanceIds=[INSTANCE_ID], WaiterConfig={'Delay': 5, 'MaxAttempts': 60})
        ec2_client.start_instances(InstanceIds=[INSTANCE_ID])
    else:
        return {'action': 'start', 'status': 'error', 'message': f'Unexpected state: {state}'}

    # Wait for running
    wait_for_instance_running()

    # Get new public IP
    public_ip = get_instance_public_ip()
    if not public_ip:
        return {'action': 'start', 'status': 'error', 'message': 'No public IP after start'}

    # Update SSM with new URL
    url = update_ssm_orpheus_url(public_ip)
    update_last_activity_timestamp()

    # Poll health endpoint
    healthy = poll_health_endpoint(public_ip)

    return {
        'action': 'start',
        'status': 'started',
        'public_ip': public_ip,
        'orpheus_url': url,
        'healthy': healthy,
    }


def handle_stop():
    """Stop the EC2 instance."""
    state = get_instance_state()

    if state == 'stopped':
        return {'action': 'stop', 'status': 'already_stopped'}

    if state in ('running', 'pending'):
        logger.info(f"Stopping instance {INSTANCE_ID}")
        ec2_client.stop_instances(InstanceIds=[INSTANCE_ID])
        return {'action': 'stop', 'status': 'stopping'}

    if state == 'stopping':
        return {'action': 'stop', 'status': 'already_stopping'}

    return {'action': 'stop', 'status': 'error', 'message': f'Unexpected state: {state}'}


def handle_idle_check():
    """Check if instance should be stopped due to inactivity."""
    state = get_instance_state()

    if state != 'running':
        logger.info(f"Instance is {state}, nothing to do")
        return {'action': 'idle_check', 'status': 'not_running', 'state': state}

    queue_depth = get_queue_depth()

    if queue_depth > 0:
        # Active work — update last activity
        update_last_activity_timestamp()
        return {
            'action': 'idle_check',
            'status': 'active',
            'queue_depth': queue_depth,
        }

    # Queue is empty — check how long it's been idle
    last_activity = get_last_activity_timestamp()
    now = int(time.time())
    idle_seconds = now - last_activity if last_activity > 0 else 0

    if last_activity == 0:
        # No recorded activity — set it now, check again next cycle
        update_last_activity_timestamp()
        return {
            'action': 'idle_check',
            'status': 'activity_initialized',
            'message': 'First check, initialized timestamp',
        }

    idle_minutes = idle_seconds / 60
    logger.info(f"Instance idle for {idle_minutes:.1f} minutes (threshold: {IDLE_TIMEOUT_MINUTES})")

    if idle_minutes >= IDLE_TIMEOUT_MINUTES:
        logger.info(f"Idle timeout reached ({idle_minutes:.1f} >= {IDLE_TIMEOUT_MINUTES} min), stopping instance")
        result = handle_stop()
        result['action'] = 'idle_check'
        result['idle_minutes'] = round(idle_minutes, 1)
        return result

    return {
        'action': 'idle_check',
        'status': 'idle_but_within_threshold',
        'idle_minutes': round(idle_minutes, 1),
        'threshold_minutes': IDLE_TIMEOUT_MINUTES,
    }


def lambda_handler(event, context):
    """
    Main handler. Determines action from event source:
    - CloudWatch Alarm (SNS) -> start
    - EventBridge scheduled rule -> idle_check
    - Direct invocation with {"action": "start"|"stop"|"idle_check"}
    """
    logger.info(f"Event: {json.dumps(event, default=str)}")

    # Direct invocation
    if isinstance(event, dict) and 'action' in event:
        action = event['action']
    # CloudWatch Alarm via SNS
    elif 'Records' in event and event['Records'][0].get('EventSource') == 'aws:sns':
        sns_message = json.loads(event['Records'][0]['Sns']['Message'])
        new_state = sns_message.get('NewStateValue', '')
        if new_state == 'ALARM':
            action = 'start'
        else:
            logger.info(f"Alarm state {new_state}, no action needed")
            return {'action': 'none', 'reason': f'Alarm state: {new_state}'}
    # EventBridge scheduled event
    elif event.get('source') == 'aws.events' or event.get('detail-type') == 'Scheduled Event':
        action = 'idle_check'
    else:
        logger.warning(f"Unknown event format, defaulting to idle_check")
        action = 'idle_check'

    logger.info(f"Executing action: {action}")

    if action == 'start':
        result = handle_start()
    elif action == 'stop':
        result = handle_stop()
    elif action == 'idle_check':
        result = handle_idle_check()
    else:
        result = {'error': f'Unknown action: {action}'}

    logger.info(f"Result: {json.dumps(result, default=str)}")
    return result

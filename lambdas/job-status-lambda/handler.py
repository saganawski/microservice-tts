"""
Job Status Lambda

Handles GET /status/{jobId} requests.
Reads job status from DynamoDB JobStatus table.
"""

import json
import boto3
import os
import logging

logger = logging.getLogger()
logger.setLevel(logging.INFO)

dynamodb = boto3.resource('dynamodb')
JOB_STATUS_TABLE = os.environ.get('JOB_STATUS_TABLE', 'JobStatus')


def lambda_handler(event, context):
    """
    API Gateway proxy handler for GET /status/{jobId}
    """
    logger.info(f"Event: {json.dumps(event)}")

    # Extract jobId from path parameters
    job_id = (event.get('pathParameters') or {}).get('jobId')

    if not job_id:
        return response(400, {'error': 'Missing jobId path parameter'})

    try:
        table = dynamodb.Table(JOB_STATUS_TABLE)
        result = table.get_item(Key={'job_id': job_id})
        item = result.get('Item')

        if not item:
            return response(404, {'error': f'Job {job_id} not found'})

        # Convert Decimal types to int/float for JSON serialization
        status_data = {
            'jobId': item['job_id'],
            'status': item.get('status', 'UNKNOWN'),
            'progress': int(item.get('progress', 0)),
            'totalChunks': int(item.get('total_chunks', 0)),
            'completedChunks': int(item.get('completed_chunks', 0)),
            'fileName': item.get('file_name', ''),
            'fileSize': int(item.get('file_size', 0)),
            'createdAt': item.get('created_at', ''),
            'updatedAt': item.get('updated_at', ''),
        }

        # Include output URL only when complete
        if item.get('status') == 'COMPLETE' and item.get('output_url'):
            status_data['outputUrl'] = item['output_url']

        # Include error message if failed
        if item.get('status') == 'FAILED' and item.get('error_message'):
            status_data['errorMessage'] = item['error_message']

        return response(200, status_data)

    except Exception as e:
        logger.error(f"Error fetching job status: {str(e)}", exc_info=True)
        return response(500, {'error': 'Internal server error'})


def response(status_code, body):
    return {
        'statusCode': status_code,
        'headers': {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Credentials': 'true',
        },
        'body': json.dumps(body),
    }

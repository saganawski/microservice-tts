#!/bin/bash

echo "NVIDIA GPU Driver Fix Script"
echo "============================"
echo ""
echo "Problem: Driver version mismatch"
echo "Kernel module: 575.64"
echo "NVML library: 580.65"
echo ""

echo "Your GPU: GeForce GTX 1050 Ti Max-Q (4GB VRAM)"
echo ""

echo "Choose a fix option:"
echo "1. Quick fix - Reboot system (recommended, easiest)"
echo "2. Remove conflicting driver and keep newer one"
echo "3. Reload kernel modules without reboot"
echo ""
read -p "Enter option (1-3): " option

case $option in
    1)
        echo ""
        echo "Please save all work and reboot your system:"
        echo "  sudo reboot"
        echo ""
        echo "After reboot, run: nvidia-smi"
        echo "If successful, you'll see your GPU listed."
        ;;

    2)
        echo ""
        echo "This will remove the older driver (575) and keep the newer one (580)."
        read -p "Continue? (y/n): " confirm
        if [[ $confirm == "y" ]]; then
            echo "Removing old driver..."
            sudo apt remove nvidia-driver-575 -y
            sudo apt autoremove -y

            echo ""
            echo "Updating initramfs..."
            sudo update-initramfs -u

            echo ""
            echo "You still need to reboot for changes to take effect."
            echo "Run: sudo reboot"
        fi
        ;;

    3)
        echo ""
        echo "Attempting to reload kernel modules (may not work without reboot)..."

        # Try to unload and reload modules
        sudo rmmod nvidia_uvm 2>/dev/null || true
        sudo rmmod nvidia_modeset 2>/dev/null || true
        sudo rmmod nvidia_drm 2>/dev/null || true
        sudo rmmod nvidia 2>/dev/null || true

        echo "Loading nvidia-580 modules..."
        sudo modprobe nvidia
        sudo modprobe nvidia_modeset
        sudo modprobe nvidia_drm
        sudo modprobe nvidia_uvm

        echo ""
        echo "Testing..."
        nvidia-smi

        if [ $? -eq 0 ]; then
            echo "Success! GPU is now accessible."
        else
            echo "Module reload failed. Please reboot instead."
        fi
        ;;

    *)
        echo "Invalid option"
        ;;
esac

echo ""
echo "Additional Notes:"
echo "- Your GTX 1050 Ti has 4GB VRAM"
echo "- This is NOT enough for VibeVoice models (need 6GB+)"
echo "- You can still use it for other ML tasks"
echo "- For VibeVoice, you'll need to:"
echo "  1. Use OpenAI API (recommended)"
echo "  2. Deploy to AWS EC2 with larger GPU"
echo "  3. Use CPU (very slow)
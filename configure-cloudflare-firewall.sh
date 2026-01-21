#!/bin/bash

# Script to allow Cloudflare IP ranges through firewall
# Run this on your remote server to fix Error 522
# Source: https://www.cloudflare.com/ips-v4/

echo "Configuring firewall to allow Cloudflare IPs..."

# Cloudflare IPv4 ranges (from https://www.cloudflare.com/ips-v4/)
CLOUDFLARE_IPS=(
    "173.245.48.0/20"
    "103.21.244.0/22"
    "103.22.200.0/22"
    "103.31.4.0/22"
    "141.101.64.0/18"
    "108.162.192.0/18"
    "190.93.240.0/20"
    "188.114.96.0/20"
    "197.234.240.0/22"
    "198.41.128.0/17"
    "162.158.0.0/15"
    "104.16.0.0/13"
    "104.24.0.0/14"
    "172.64.0.0/13"
    "131.0.72.0/22"
)

# Allow Cloudflare IPs on port 443
for ip in "${CLOUDFLARE_IPS[@]}"; do
    echo "Adding rule for $ip"
    sudo iptables -I INPUT -p tcp --dport 443 -s "$ip" -j ACCEPT
done

# Check if firewalld is running
if systemctl is-active --quiet firewalld; then
    echo "Detected firewalld, adding rules..."
    for ip in "${CLOUDFLARE_IPS[@]}"; do
        sudo firewall-cmd --permanent --add-rich-rule="rule family='ipv4' source address='$ip' port protocol='tcp' port='443' accept"
    done
    sudo firewall-cmd --reload
    echo "Firewalld rules added and reloaded"
fi

# Save iptables rules
if command -v netfilter-persistent &> /dev/null; then
    sudo netfilter-persistent save
    echo "iptables rules saved with netfilter-persistent"
elif command -v iptables-save &> /dev/null; then
    sudo iptables-save | sudo tee /etc/iptables/rules.v4 > /dev/null
    echo "iptables rules saved to /etc/iptables/rules.v4"
fi

echo "Firewall configuration complete!"
echo ""
echo "Verifying port 443 is open..."
sudo ss -tlnp | grep :443

echo ""
echo "Current iptables rules for port 443:"
sudo iptables -L INPUT -n | grep 443


#!/usr/bin/env python3

import socket

HOST = "0.0.0.0"  # Listen on all available interfaces
PORT = 5140  # Custom UDP port


def start_syslog_receiver(host, port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, port))
    print(f"Listening for syslog messages on udp://{host}:{port}...")

    try:
        while True:
            data, address = sock.recvfrom(4096)  # Buffer size is 4096 bytes
            message = data.decode("utf-8", errors="ignore").strip()
            print(f"Received from {address}: {message}")
    except KeyboardInterrupt:
        print("\nStopping syslog receiver.")
    finally:
        sock.close()


if __name__ == "__main__":
    start_syslog_receiver(HOST, PORT)

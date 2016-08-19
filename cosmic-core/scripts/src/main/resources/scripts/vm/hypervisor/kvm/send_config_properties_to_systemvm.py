#!/usr/bin/python

# This script connects to the system vm socket and writes the
# authorized_keys and cmdline data to it. The system VM then
# reads it from /dev/vport0p1 in cloud_early_config
#

import argparse
import os
import json
import base64
import socket

SOCK_FILE = "/var/lib/libvirt/qemu/{name}.agent"
PUB_KEY_FILE = "/root/.ssh/id_rsa.pub.cloud"
MESSAGE = "pubkey:{key}\ncmdline:{cmdline}\n"
CMDLINE_FILE = "/var/cache/cloud/cmdline_incoming"

FILE_OPEN_WRITE = """{"execute":"guest-file-open", "arguments":{"path":"%s","mode":"w+"}}"""
FILE_WRITE = """{"execute":"guest-file-write", "arguments":{"handle":%s,"buf-b64":"%s"}}"""
FILE_CLOSE = """{"execute":"guest-file-close", "arguments":{"handle":%s}}"""


# This is only needed temporarily until the new systemvm templates won't have an endless-loop for the old patchviasocket thing.
def write_to_legacy_socket():
    try:
        sock_file = SOCK_FILE.format(name=arguments.name)
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect(sock_file)
        s.sendall("cmdline:Compatibility\n")
        s.close()
    except IOError as e:
        print("ERROR: unable to connect to {0} - {1}".format(sock_file, e.strerror))
        return 1

def EXE(param):
    cmd = """virsh qemu-agent-command %s '%s' """ % (arguments.name, param)
    print "Exe command:%s" % cmd
    stream = os.popen(cmd).read()
    return None if not stream else json.loads(stream)


def write_guest_file(path, content):
    file_handle = -1
    try:
        file_handle = EXE(FILE_OPEN_WRITE % path)["return"]
        write_count = EXE(FILE_WRITE % (file_handle, content))["return"]["count"]
    except Exception as ex:
        print Exception, ":", ex
        return -1
    finally:
        EXE(FILE_CLOSE % file_handle)
    return write_count

def generate_content(key_file, cmdline):
    if not os.path.exists(key_file):
        print("ERROR: ssh public key not found on host at {0}".format(key_file))
        return 1

    try:
        with open(key_file, "r") as f:
            pub_key = f.read()
    except IOError as e:
        print("ERROR: unable to open {0} - {1}".format(key_file, e.strerror))
        return 1

    # Keep old substitution from perl code:
    cmdline = cmdline.replace("%", " ")

    msg = MESSAGE.format(key=pub_key, cmdline=cmdline)
    return msg


def write_file():
    try:
        content = base64.standard_b64encode(generate_content(PUB_KEY_FILE, arguments.cmdline))
        write_count = write_guest_file(CMDLINE_FILE, content)
        print write_count > 0
    except Exception as ex:
        print "Warning: it was not possible to write to the Qemu Guest Agent at this time. Will try again later."


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Send configuration to system VM socket")
    parser.add_argument("-n", "--name", required=True, help="Name of VM")
    parser.add_argument("-p", "--cmdline", required=True, help="Command line")

    arguments = parser.parse_args()
    write_to_legacy_socket()
    write_file()

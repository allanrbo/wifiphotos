#!/usr/bin/env python

from argparse import ArgumentParser
from datetime import datetime
from os import listdir, utime, remove
from os.path import isfile, join, getmtime, getsize
from shutil import copyfileobj
from urllib2 import Request, urlopen
import json
import os
import time
import random
import string

def mirror(address, localDir, collection, overwrite, delete, deleteConfirmed):
    # Log in.
    authtoken = ''.join(random.choice(string.ascii_lowercase) for i in range(50))
    pin = random.randint(1000,9999)
    print("Press allow on your device if you see PIN " + str(pin))
    req = Request("http://" + address + "/api/login", data=json.dumps({"pin": pin, "token": authtoken}))
    req.get_method = lambda: "POST"
    req.add_header("Content-Type", "application/json")
    urlopen(req).read()

    # Get all buckets.
    req = Request("http://" + address + "/api/buckets")
    req.add_header("Cookie", "authtoken=" + authtoken)
    buckets = json.loads(urlopen(req).read())

    # Get ID of bucket we are interested in.
    bucketId = 0
    for b in buckets:
        if b["displayName"] == collection:
            bucketId = b["id"]
            break

    if bucketId == 0:
        "Could not find collection " + collection

    # Get all images in bucket.
    req = Request("http://" + address + "/api/buckets/" + str(bucketId))
    req.add_header("Cookie", "authtoken=" + authtoken)
    images = json.loads(urlopen(req).read())

    localFiles = [f for f in listdir(localDir) if isfile(join(localDir, f))]

    log("Images remotely: " + str(len(images)))
    log("Images locally: " + str(len(localFiles)))

    remoteFiles = []
    for image in images:
        remoteFiles.append(image["name"])
        path = join(localDir, image["name"])
        missing = image["name"] not in localFiles
        wrongSize = not missing and getsize(path) != image["size"]
        wrongTimestamp = not missing and abs(getmtime(path) - image["dateModified"]) > 4  # Windows may have some granularity issues, so using this tolerance
        if missing or (overwrite and (wrongSize or wrongTimestamp)):
            log("Downloading " + image["name"])
            req = Request("http://" + address + "/api/images/" + str(image["imageId"]) + "?size=full")
            req.add_header("Cookie", "authtoken=" + authtoken)
            start = time.time()
            length = 0
            r = urlopen(req)
            with open(path, 'wb') as fp:
                copyfileobj(r, fp)
                length = fp.tell()
            r.close()
            end = time.time()
            mbPerSec = length/1024.0/1024.0 / ((end - start))
            log("Downloaded with " + "{:.2f}".format(mbPerSec) + " MiB/sec")
            utime(path, (image["dateModified"], image["dateModified"]))

    if delete or deleteConfirmed:
        extraFiles = list(set(localFiles)-set(remoteFiles))
        if len(extraFiles) > 0:
            for file in extraFiles:
                path = join(localDir, file)
                log("Extra local file: " + path)

            confirmed = deleteConfirmed
            if not confirmed:
                confirmed = confirm("Delete extra local files?")

            if confirmed:
                for file in extraFiles:
                    path = join(localDir, file)
                    log("Deleting extra local file: " + path)
                    remove(path)

    log("Done")

def log(msg):
    print("[" + datetime.utcnow().isoformat() + "] " + msg)

# https://code.activestate.com/recipes/541096-prompt-the-user-for-confirmation/
def confirm(prompt=None, resp=False):
    if prompt is None:
        prompt = 'Confirm'

    if resp:
        prompt = '%s [%s]|%s: ' % (prompt, 'y', 'n')
    else:
        prompt = '%s [%s]|%s: ' % (prompt, 'n', 'y')

    while True:
        ans = raw_input(prompt)
        if not ans:
            return resp
        if ans not in ['y', 'Y', 'n', 'N']:
            print('please enter y or n.')
            continue
        if ans == 'y' or ans == 'Y':
            return True
        if ans == 'n' or ans == 'N':
            return False

def main():
    dirExample = "/home/somebody/Pictures/Phone"
    if os.name == "nt":
        dirExample = "C:\\Users\\somebody\\Pictures\\Phone"

    parser = ArgumentParser(description="Mirror an Android photo collection to a local directory via WifiPhotos.")
    parser.add_argument("--address", required=True, help="The IP and port of your Android phone running WifiPhotos. Example: 192.168.1.123:8080")
    parser.add_argument("--localdir", required=True, help="Local directory to mirror to. Example: " + dirExample)
    parser.add_argument("--collection", default="Camera", help="Name of collection to mirror. Example: Camera")
    parser.add_argument("--overwrite", dest="overwrite", action="store_true", help="Whether to overwrite any local files with the same name if size or timestamp differs from remote.")
    parser.add_argument("--delete", dest="delete", action="store_true", help="Whether to delete extra files locally that are not present remote.")
    parser.add_argument("--delete-skip-confirm", dest="deleteConfirmed", action="store_true", help="Assume 'yes' to confirm to delete extra files locally that are not present remote.")
    args = parser.parse_args()
    mirror(args.address, args.localdir, args.collection, args.overwrite, args.delete, args.deleteConfirmed)


if __name__ == "__main__":
    main()

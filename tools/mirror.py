#!/usr/bin/env python

from argparse import ArgumentParser
from datetime import datetime
from os import listdir, utime, remove
from os.path import isfile, join, getmtime, getsize
from shutil import copyfileobj
from urllib2 import Request, urlopen
import json
import os

def mirror(address, localDir, collection, deleteAndOverwrite):
    buckets = json.loads(urlopen("http://" + address + "/api/buckets").read())

    bucketId = 0
    for b in buckets:
        if b["displayName"] == collection:
            bucketId = b["id"]
            break

    if bucketId == 0:
        "Could not find collection " + collection

    images = json.loads(urlopen("http://" + address + "/api/buckets/" + str(bucketId)).read())
    remoteFiles = []
    localFiles = [f for f in listdir(localDir) if isfile(join(localDir, f))]

    log("Images remotely: " + str(len(images)))
    log("Images locally: " + str(len(localFiles)))

    for image in images:
        remoteFiles.append(image["name"])
        path = join(localDir, image["name"])
        missing = image["name"] not in localFiles
        wrongSize = not missing and getsize(path) != image["size"]
        wrongTimestamp = not missing and abs(getmtime(path) - image["dateModified"]) > 4  # Windows may have some granularity issues, so using this tolerance
        if missing or (deleteAndOverwrite and (wrongSize or wrongTimestamp)):
            log("Downloading " + image["name"])
            req = urlopen("http://" + address + "/api/images/" + str(image["imageId"]) + "?size=full")
            with open(path, 'wb') as fp:
                copyfileobj(req, fp)
            req.close()
            utime(path, (image["dateModified"], image["dateModified"]))

    if deleteAndOverwrite:
        extraFiles = list(set(localFiles)-set(remoteFiles))
        for file in extraFiles:
            path = join(localDir, file)
            log("Deleting extra local file " + path)
            remove(path)

    log("Done")

def log(msg):
    print("[" + datetime.utcnow().isoformat() + "] " + msg)

def main():
    dirExample = "/home/somebody/Pictures/Phone"
    if os.name == "nt":
        dirExample = "C:\\Users\\somebody\\Pictures\\Phone"

    parser = ArgumentParser(description="Mirror an Android photo collection to a local directory via WifiPhotos.")
    parser.add_argument("--address", required=True, help="The IP and port of your Android phone running WifiPhotos. Example: 192.168.1.123:8080")
    parser.add_argument("--localdir", required=True, help="Local directory to mirror to. Example: " + dirExample)
    parser.add_argument("--collection", default="Camera", help="Name of collection to mirror. Example: Camera")
    parser.add_argument("--delete-and-overwrite", dest="deleteAndOverwrite", action="store_true", help="Whether to delete extra files locally and overwrite local files if size or timestamp differs from remote.")
    args = parser.parse_args()
    mirror(args.address, args.localdir, args.collection, args.deleteAndOverwrite)


if __name__ == "__main__":
    main()

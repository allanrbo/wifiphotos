var ImageGrid = {
    scrollHandlerPending: false,
    imageSize: 0,
    selectedImageIDs: [],
    shouldScrollToTimestamp: false,
    scrolledToTimestamp: 0,
    previouslyViewedCollection: 0,
    previouslyViewedScrolledToTimestamp: 0,

    oninit: function() {
        Bucket.list = [];
        Bucket.currentId = null;
        Image.list = [];

        Bucket.loadList().then(function() {
            var savedBucketId = localStorage.getItem("bucketId");
            for (var i = 0; i < Bucket.list.length; i++) {
                if (Bucket.list[i].id == savedBucketId) {
                    // Saved bucket ID was valid. Restoring.
                    Bucket.currentId = savedBucketId;
                    break;
                }
            }

            Image.loadList(Bucket.currentId);
        });

        window.addEventListener('scroll', ImageGrid.scrollHandler, false);
        window.addEventListener('resize', ImageGrid.scrollHandler, false);
        window.addEventListener('keydown', function(e) {
            if (e.keyCode == 46) { // Delete key
                ImageGrid.deleteSelected();
            }
            if (e.keyCode == 27) { // Esc key
                ImageGrid.selectedImageIDs = [];
                m.redraw();
            }
        }, false);

        ImageGrid.imageSize = localStorage.getItem("imageSize");
        if (ImageGrid.imageSize == null) {
            ImageGrid.imageSize = 600;
            if (window.devicePixelRatio >= 2) {
                ImageGrid.imageSize = 1000;
            }
        }

        ImageGrid.scrolledToTimestamp = sessionStorage.getItem("scrolledToTimestamp");
        if (ImageGrid.scrolledToTimestamp != null) {
            ImageGrid.shouldScrollToTimestamp = true;
        } else {
            ImageGrid.scrolledToTimestamp = 0;
        }
    },

    view: function(vnode) {
        var loading = false;
        if (!Bucket.listLoaded || !Image.listLoaded) {
            loading = true;
        }

        if (Ping.lostConnection) {
            return m(".login", [
                m("div", "Lost connection to device. Check that the app is still open."),
                m("button", {
                    onclick: function() {
                        Ping.ping()
                        .then(function() {
                            ImageGrid.shouldScrollToTimestamp = true;
                            Image.list = [];
                            Image.loadList(Bucket.currentId);
                        });
                    }
                }, "Retry")
            ]);
        }

        var isTrash = Bucket.currentId == "trash";

        var docWidth = document.body.clientWidth;

        var scrollTop = document.documentElement.scrollTop;
        var scrollBottom = scrollTop + window.innerHeight;

        var imgSize = Math.floor(parseInt(ImageGrid.imageSize) / window.devicePixelRatio);
        var imgMargin = 3;
        var imgPadding = 8;
        var imgBoxSize = imgMargin + imgPadding + imgSize + imgPadding + imgMargin;

        var columns = Math.floor(docWidth / imgBoxSize);

        var marginTop = 37;
        var marginLeft = Math.floor((docWidth - imgBoxSize * columns) / 2);
        if (imgSize > docWidth) {
            marginLeft = 0;
        }
        var curCol = 0;
        var curRow = 0;
        var imgBoxPositions = {};
        var imgBoxPositionsToDraw = [];
        var lastTop = 0;
        var firstVisibleImageBoxPos = null;
        for (var i = 0; i < Image.list.length; i++) {
            var image = Image.list[i];
            var resizedDims = Image.calcNewDimensions(image.width, image.height, imgSize);

            var imgBoxPos = {
                image: image,
                left: curCol*imgBoxSize + marginLeft,
                top: curRow*imgBoxSize + marginTop,
                width: resizedDims.width,
                height: resizedDims.height
            };

            imgBoxPositions[image.imageId] = imgBoxPos;

            // Scroll to a previously saved position if this is the right image box to scroll to.
            if (ImageGrid.shouldScrollToTimestamp && imgBoxPos.image.dateTaken <= ImageGrid.scrolledToTimestamp) {
                var f = function(scrollTop) {
                    return function() {
                        document.documentElement.scrollTop = scrollTop - 50;
                    }
                }(imgBoxPos.top);
                setTimeout(f, 0);
                ImageGrid.shouldScrollToTimestamp = false;
            }

            // If an image box is more than halfway on the screen, we'll mark it as visible.
            var bottom = imgBoxPos.top + imgSize;
            if (scrollTop - imgSize * 0.5 <= imgBoxPos.top && bottom <= scrollBottom + imgSize * 0.5) {
                imgBoxPos.visible = true;
                if (firstVisibleImageBoxPos == null) {
                    firstVisibleImageBoxPos = imgBoxPos;
                }
            }

            // If an image box is within a short distance from the visible screen, we'll draw the image box.
            if (scrollTop - imgSize * 2 <= imgBoxPos.top && bottom <= scrollBottom + imgSize * 2) {
                imgBoxPositionsToDraw.push(imgBoxPos);
            }

            curCol++;
            if (curCol >= columns) {
                curRow++;
                curCol = 0;
            }

            lastTop = imgBoxPos.top;
        }

        // Save the timestamp of the image in the top right corner, in case we need to scroll to it later.
        if (firstVisibleImageBoxPos != null && ImageGrid.shouldScrollToTimestamp == false) {
            ImageGrid.scrolledToTimestamp = firstVisibleImageBoxPos.image.dateTaken;
            sessionStorage.setItem("scrolledToTimestamp", ImageGrid.scrolledToTimestamp);
        }

        // Only after the image list is loaded we are done with trying to scroll back to the image after the scrolledToTimestamp timestamp.
        if (Image.listLoaded && Image.list.length > 0) {
            ImageGrid.shouldScrollToTimestamp = false;
        }

        var pageLength = 0;
        if (Image.list.length > 0) {
            pageLength = lastTop + imgBoxSize;
        }

        return [
            /*
             * Main image grid.
             */
            m("#images", {
                style: "height:" + pageLength + "px;",
                onclick: function(e) {
                    if (e.target.id == "images") {
                        ImageGrid.selectedImageIDs = [];
                    }
                },
                onselect: function() {
                    return false;
                },
                onupdate: function(vnode) {
                    // The virtual DOM caused problems with img elements being reused, causing a flash of the wrong image until the new image loads.
                    // Similar issue with flickering when image div's are removed from the front.
                    // For example the element that used to be <img src="5"> may now be <img src="10">, even though <img src="10"> maintains its absolute position.
                    // Therefore manually controlling the DOM inside the #images div.

                    // Pre-compute the intended CSS for this image box.
                    for (var i = 0; i < imgBoxPositionsToDraw.length; i++) {
                        var imgBoxPos = imgBoxPositionsToDraw[i];
                        imgBoxPos.style = ("width:" + imgSize + "px;"
                            + "height:" + imgSize + "px;"
                            + "top:" + imgBoxPos.top + "px;"
                            + "left:" + imgBoxPos.left + "px;");
                    }

                    // Remove image box DOM elements that should no longer be drawn.
                    for (var i = 0; i < vnode.dom.children.length; i++) {
                        var imgDiv = vnode.dom.children[i];

                        var found = false;
                        for (var j = 0; j < imgBoxPositionsToDraw.length; j++) {
                            var imgBoxPos = imgBoxPositionsToDraw[j];
                            if (imgDiv.dataset.imageId == imgBoxPos.image.imageId && imgDiv.dataset.style == imgBoxPos.style) {
                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            vnode.dom.removeChild(imgDiv);
                            i--;
                        }
                    }

                    // Draw new image box DOM elements.
                    for (var i = 0; i < imgBoxPositionsToDraw.length; i++) {
                        var imgBoxPos = imgBoxPositionsToDraw[i];
                        var imageId = imgBoxPos.image.imageId;

                        // Does this image box already exist in the DOM?
                        var found = false;
                        for (var j = 0; j < vnode.dom.children.length; j++) {
                            var imgDiv = vnode.dom.children[j];
                            if (imgDiv.dataset.imageId == imageId && imgDiv.dataset.style == imgBoxPos.style) {
                                found = true;
                                prevDomElem = imgDiv;
                                break;
                            }
                        }
                        if (found) {
                            continue;
                        }

                        var imgDiv = document.createElement("div");
                        imgDiv.className = "image";
                        imgDiv.dataset.imageId = imageId;
                        imgDiv.dataset.style = imgBoxPos.style;
                        imgDiv.style = imgBoxPos.style;
                        imgDiv.onmousedown = function() {
                            var imageId = this.dataset.imageId;
                            var alreadySelected = false;
                            for (var j = 0; j < ImageGrid.selectedImageIDs.length; j++) {
                                if (ImageGrid.selectedImageIDs[j] == imageId) {
                                    alreadySelected = true;
                                    ImageGrid.selectedImageIDs.splice(j, 1);
                                    break;
                                }
                            }

                            if (!alreadySelected) {
                                ImageGrid.selectedImageIDs.push(imageId);
                            }
                            m.redraw();
                        };

                        var imglabel = document.createElement("div");
                        imglabel.className = "imglabel";
                        imglabel.innerText = imgBoxPos.image.name;
                        imgDiv.appendChild(imglabel);

                        vnode.dom.appendChild(imgDiv);
                    }

                    // Update the "selected"-class on image boxes.
                    for (var i = 0; i < vnode.dom.children.length; i++) {
                        var imgDiv = vnode.dom.children[i];
                        var shouldBeSelected = false;
                        for (var j = 0; j < ImageGrid.selectedImageIDs.length; j++) {
                            if (ImageGrid.selectedImageIDs[j] == imgDiv.dataset.imageId) {
                                shouldBeSelected = true;
                                break;
                            }
                        }

                        if (shouldBeSelected && !imgDiv.classList.contains("selected")) {
                            imgDiv.classList.add("selected");
                        } else if (!shouldBeSelected && imgDiv.classList.contains("selected")) {
                            imgDiv.classList.remove("selected");
                        }
                    }

                    // Add img elements progressively, and not all at once.
                    // This is because in case the user scrolls away, we don't want a lot of wasted HTTP requests being already enqueued.
                    var loadNextImgs = function() {
                        var maxConcurrent = 8;

                        // Get how many image are currently in progress of being loaded.
                        var currentLoadingCount = 0;
                        for (var i = 0; i < vnode.dom.children.length; i++) {
                            var imgDiv = vnode.dom.children[i];
                            for (var j = 0; j < imgDiv.children.length; j++) {
                                if (imgDiv.children[j].tagName == "IMG" && !imgDiv.children[j].complete) {
                                    currentLoadingCount++;
                                    break;
                                }
                            }
                        }

                        var drawImgIfNeeded = function(imgDiv) {
                            var imageId = imgDiv.dataset.imageId;
                            var alreadyHasImg = false;
                            var recreatingBroken = false;
                            for (var j = 0; j < imgDiv.children.length; j++) {
                                if (imgDiv.children[j].tagName == "IMG") {
                                    // Is this a broken image element?
                                    // This is a workaround for a bug that I have not yet solved, where the server sometimes sends incomplete images.
                                    if (imgDiv.children[j].complete && (imgDiv.children[j].naturalWidth == 0 || imgDiv.children[j].naturalHeight == 0)) {
                                        if (imgDiv.dataset.recreatingInProgress == "1") {
                                        } else if (imgDiv.dataset.retries == undefined) {
                                            imgDiv.dataset.retries = 1;
                                        } else if (imgDiv.dataset.retries++ > 5) {
                                            alreadyHasImg = true;
                                            break;
                                        }
                                        console.log("Found broken image " + imgDiv.dataset.imageId + ". Recreating img element.");
                                        imgDiv.removeChild(imgDiv.children[j]);
                                        recreatingBroken = true;
                                        break;
                                    }

                                    alreadyHasImg = true;
                                    break;
                                }
                            }

                            if (alreadyHasImg) {
                                return;
                            }

                            var imgBoxPos = imgBoxPositions[imageId];

                            var extraTimestamp = recreatingBroken ? "&ts=" + (new Date().getTime()) : "";

                            img = document.createElement("img");
                            imgDiv.appendChild(img);
                            img.src = apiUrl + "/images/" + (isTrash ? "trash/" : "") + imageId + "/" + imgBoxPos.image.name + "?size=" + ImageGrid.imageSize + extraTimestamp;
                            img.style = "width:" + imgBoxPos.width + "px;height:" + imgBoxPos.height + "px;";
                            if (img.complete) {
                                return;
                            } else {
                                currentLoadingCount++;
                                img.addEventListener('load', function(e) {
                                    loadNextImgs();
                                });
                                img.addEventListener('error', function(e) {
                                    Ping.ping()
                                    .then(function(){
                                        loadNextImgs();
                                    });
                                });
                            }
                        }

                        // Prioritize first to draw img elements in boxes that are currently visible.
                        for (var i = 0; i < vnode.dom.children.length; i++) {
                            if (currentLoadingCount >= maxConcurrent) {
                                return;
                            }

                            var imgDiv = vnode.dom.children[i];

                            var visible = false;
                            for (var j = 0; j < imgBoxPositionsToDraw.length; j++) {
                                var imgBoxPos = imgBoxPositionsToDraw[j];
                                if (imgDiv.dataset.imageId == imgBoxPos.image.imageId && imgBoxPos.visible) {
                                    visible = true;
                                    break;
                                }
                            }

                            if (visible) {
                                drawImgIfNeeded(vnode.dom.children[i]);
                            }
                        }

                        // If all the visible img elements are already loading and we haven't met the maxConcurrent limit, then draw some off screen images.
                        for (var i = 0; i < vnode.dom.children.length; i++) {
                            if (currentLoadingCount >= maxConcurrent) {
                                return;
                            }

                            drawImgIfNeeded(vnode.dom.children[i]);
                        }

                    }
                    loadNextImgs();
                }
            }),

            /*
             * Top bar.
             */
            m(".topbar", [
                m(".control", [
                    m("label[for=collection]", "Collection"),
                    " ",
                    m("select#collection",
                        {
                            onchange: function(e) {
                                Image.list = [];
                                Bucket.currentId = e.target.value;
                                localStorage.setItem("bucketId", Bucket.currentId);
                                ImageGrid.selectedImageIDs = [];
                                ImageGrid.scrolledToTimestamp = 0;
                                sessionStorage.setItem("scrolledToTimestamp", ImageGrid.scrolledToTimestamp);
                                Image.loadList(Bucket.currentId);
                            },
                        },
                        [
                            Bucket.list.map(function(bucket) {
                                // This is a workaround. This shouldn't be necessary, because we are setting the "selected" attribute below, but for some reason it's not enough.
                                if (Bucket.currentId == bucket.id) {
                                    setTimeout(function() {
                                        document.getElementById("collection").value = Bucket.currentId;
                                    }, 0);
                                }

                                return m("option", {value: bucket.id, selected: (Bucket.currentId == bucket.id ? "selected" : "")}, bucket.displayName);
                            }),
                            m("option", {value: "trash", selected: (Bucket.currentId == "trash" ? "selected" : "")}, "Trash")
                        ]
                    )
                ]),
                m(".control", [
                    m("label[for=size]", "Size"),
                    " ",
                    m("select#size",
                        {
                            onchange: function(e) {
                                ImageGrid.imageSize = e.target.value;
                                localStorage.setItem("imageSize", ImageGrid.imageSize);
                                ImageGrid.shouldScrollToTimestamp = true;
                                ImageGrid.scrollHandler();
                            }
                        },
                        [100,200,300,400,600,800,900,1000,1400,1800,2200,2600].map(function(s) {
                            return m("option", {value: s, selected: ImageGrid.imageSize == s ? "selected" : ""}, s + " px");
                        })
                    )
                ]),
                m(".control" + (ImageGrid.selectedImageIDs.length != 1 ? ".disabled" : ""), [
                    m("span.fa.fa-arrows-alt", { onclick: ImageGrid.viewFullRes }
                    ),
                    " ",
                    m("a", { onclick: ImageGrid.viewFullRes }, "View full resolution")
                ]),
                !isTrash ? [
                    m(".control", [
                        m("span.fa.fa-trash-o", { onclick: ImageGrid.deleteSelected } ),
                        " ",
                        m("a", { onclick: ImageGrid.deleteSelected }, "Move to trash (" + ImageGrid.selectedImageIDs.length + " selected)")
                    ]),
                    m(".control", [
                        m("a", { onclick: ImageGrid.viewTrash }, "View trash")
                    ])
                ] : [
                    m(".control", [
                        m("span.fa.fa-reply", { onclick: ImageGrid.restoreSelectedFromTrash } ),
                        " ",
                        m("a", { onclick: ImageGrid.restoreSelectedFromTrash }, "Restore from trash")
                    ]),
                    m(".control", [
                        m("span.fa.fa-times", { onclick: ImageGrid.deleteSelected }
                        ),
                        " ",
                        m("a", { onclick: ImageGrid.deleteSelected }, "Delete permanently (" + ImageGrid.selectedImageIDs.length + " selected)")
                    ]),
                    m(".control", [
                        m("span.fa.fa-times", { onclick: ImageGrid.deleteAllTrash }
                        ),
                        " ",
                        m("a", { onclick: ImageGrid.deleteAllTrash }, "Delete all trash permanently")
                    ]),
                    m(".control", [
                        m("span.fa.fa-arrow-left", { onclick: ImageGrid.returnToPreviouslyViewedCollection }
                        ),
                        " ",
                        m("a", { onclick: ImageGrid.returnToPreviouslyViewedCollection }, "Return")
                    ])
                ]
            ]),

            // This needs to be last, to overlay everything else.
            loading ? m(".spinneroverlay", m(".spinner")) : null
        ];
    },

    scrollHandler: function() {
        if (!ImageGrid.scrollHandlerPending) {
            ImageGrid.scrollHandlerPending = true;
            setTimeout(function() {
                ImageGrid.scrollHandlerPending = false;
                m.redraw();
            }, 400);
        }
    },

    deleteSelected: function() {
        // Are we currently viewing the trash directory?
        var isTrash = Bucket.currentId == "trash";
        if (isTrash) {
            var r = confirm("Permanently delete the selected items?");
            if (r != true) {
                return;
            }
        }

        var promises = [];
        for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
            promises.push(Image.delete(ImageGrid.selectedImageIDs[i], isTrash));
        }

        Promise.all(promises).then(function() {
            Image.loadList(Bucket.currentId);
            ImageGrid.selectedImageIDs = [];
       });
    },

    deleteAllTrash: function() {
        // Are we currently viewing the trash directory?
        var isTrash = Bucket.currentId == "trash";
        if (!isTrash) {
            return;
        }

        var r = confirm("Permanently delete all trash?");
        if (r != true) {
            return;
        }

        // Load trash list again just to be 100% sure that we are only deleting trash.
        Bucket.currentId = "trash";
        Image.loadList("trash")
        .then(function() {
            var promises = [];
            for (var i = 0; i < Image.list.length; i++) {
                promises.push(Image.delete(Image.list[i].imageId, isTrash));
            }

            Promise.all(promises).then(function() {
                Bucket.currentId = "trash";
                Image.loadList("trash");
                ImageGrid.selectedImageIDs = [];
           });
        });
    },

    restoreSelectedFromTrash: function() {
        var promises = [];
        for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
            promises.push(Image.restore(ImageGrid.selectedImageIDs[i]));
        }

        Promise.all(promises)
        .then(function() {
            Image.loadList(Bucket.currentId);
            ImageGrid.selectedImageIDs = [];
       });
    },

    viewTrash: function() {
        ImageGrid.previouslyViewedCollection = Bucket.currentId;
        ImageGrid.previouslyViewedScrolledToTimestamp = ImageGrid.scrolledToTimestamp;
        ImageGrid.scrolledToTimestamp = 0;
        sessionStorage.setItem("scrolledToTimestamp", ImageGrid.scrolledToTimestamp);
        Image.list = [];
        Bucket.currentId = "trash";
        ImageGrid.selectedImageIDs = [];
        Image.loadList(Bucket.currentId);
    },

    returnToPreviouslyViewedCollection: function() {
        Bucket.currentId = ImageGrid.previouslyViewedCollection;
        ImageGrid.scrolledToTimestamp = ImageGrid.previouslyViewedScrolledToTimestamp;
        sessionStorage.setItem("scrolledToTimestamp", ImageGrid.scrolledToTimestamp);
        ImageGrid.shouldScrollToTimestamp = true;
        ImageGrid.selectedImageIDs = [];
        Image.list = [];
        Image.loadList(Bucket.currentId);
    },

    viewFullRes: function() {
        if (ImageGrid.selectedImageIDs.length != 1) {
            return;
        }

        var imageId = ImageGrid.selectedImageIDs[0];

        var name = "";
        for (var i = 0; i < Image.list.length; i++) {
            if (Image.list[i].imageId == imageId) {
                name = Image.list[i].name;
            }
        }

        var isTrash = Bucket.currentId == "trash";
        window.location.href = apiUrl + "/images/" + (isTrash ? "trash/" : "") + imageId + "/" + name + "?size=full";
    }
}

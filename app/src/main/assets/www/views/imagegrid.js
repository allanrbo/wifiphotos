var ImageGrid = {

    scrollHandlerPending: false,
    imageSize: 600,
    selectedImageIDs: [],
    previouslyViewedCollection: 0,
    imagesBoxesToRenderStart: 0,
    imagesBoxesToRenderEnd: 30,

    oninit: function() {
        Bucket.list = [];
        Bucket.currentId = null;
        Image.list = [];
        Bucket.loadList().then(function() {
            Image.loadList(Bucket.currentId).then(function() {
            })
        });

        window.addEventListener('scroll', ImageGrid.scrollHandler, false);
        window.addEventListener('resize', ImageGrid.scrollHandler, false);
        window.addEventListener('keydown', function(e) {
            if (e.keyCode == 46) { // Delete key
                ImageGrid.deleteSelected();
            }
        }, false);
    },

    view: function(vnode) {
        var loading = false;
        if (!Bucket.listLoaded || !Image.listLoaded) {
            loading = true;
        }

        var isTrash = Bucket.currentId == "trash";

        var docWidth = document.body.clientWidth;

        var scrollTop = document.documentElement.scrollTop;
        var scrollBottom = scrollTop + window.innerHeight;

        var imgSize = parseInt(ImageGrid.imageSize);
        var imgMargin = 3;
        var imgPadding = 8;
        var imgBoxSize = imgMargin + imgPadding + imgSize + imgPadding + imgMargin;

        var columns = Math.floor(docWidth / imgBoxSize);

        var marginTop = 37;
        var marginLeft = (docWidth - imgBoxSize * columns) / 2;
        var curCol = 0;
        var curRow = 0;
        var imgBoxPositions = [];
        var imgBoxPositionsToDraw = [];
        for (var i = 0; i < Image.list.length; i++) {

            var imgBoxPos = {
                image: Image.list[i],
                left: curCol*imgBoxSize + marginLeft,
                top: curRow*imgBoxSize + marginTop,
            };

            imgBoxPositions.push(imgBoxPos);

            var bottom = imgBoxPos.top + imgSize;
            if (scrollTop - imgSize * 2 <= imgBoxPos.top && bottom <= scrollBottom + imgSize * 2) {
                imgBoxPositionsToDraw.push(imgBoxPos);
            }

            curCol++;
            if (curCol >= columns) {
                curRow++;
                curCol = 0;
            }

        }

        var pageLength = 0;
        if (imgBoxPositions.length > 0) {
            pageLength = imgBoxPositions[imgBoxPositions.length-1].top + imgBoxSize;
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
                    // The virtual DOM causes problems with img elements being reused, causing a flash of the wrong image until the new image loads.
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

                        img = document.createElement("img");
                        img.src = apiUrl + "/images/" + (isTrash ? "trash/" : "") + imageId + "?size=" + ImageGrid.imageSize;
                        imgDiv.appendChild(img);

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
                                ImageGrid.selectedImageIDs = [];
                                Image.loadList(Bucket.currentId);
                            },
                        },
                        [
                            Bucket.list.map(function(bucket) {
                                return m("option", {value: bucket.id, selected: Bucket.currentId == bucket.id ? "selected" : ""}, bucket.displayName);
                            }),
                            m("option", {value: "trash", selected: Bucket.currentId == "trash" ? "selected" : ""}, "Trash")
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
                                ImageGrid.scrollHandler();
                            }
                        },
                        [100,200,300,400,500,600,700,800,900,1000].map(function(s) {
                            var selected = "";
                            if (ImageGrid.imageSize == s) {
                                selected = "selected";
                            }
                            return m("option", {value: s, selected: selected}, s + " px");
                        })
                    )
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

    restoreSelectedFromTrash: function() {
        var promises = [];
        for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
            promises.push(Image.restore(ImageGrid.selectedImageIDs[i]));
        }

        Promise.all(promises)
        .then(function(a) {
            Image.loadList(Bucket.currentId);
            ImageGrid.selectedImageIDs = [];
       });
    },

    viewTrash: function() {
        ImageGrid.previouslyViewedCollection = Bucket.currentId;
        Image.list = [];
        Bucket.currentId = "trash";
        ImageGrid.selectedImageIDs = [];
        Image.loadList(Bucket.currentId);
    },

    returnToPreviouslyViewedCollection: function() {
        Bucket.currentId = ImageGrid.previouslyViewedCollection;
        ImageGrid.selectedImageIDs = [];
        Image.loadList(Bucket.currentId);
    }

}

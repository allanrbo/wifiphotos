var ImageGrid = {

    checkImagesInViewPending: false,
    imageSize: 500,
    imageQuality: "high",
    selectedImageIDs: [],
    previouslyViewedCollection: 0,

    oninit: function() {
        Bucket.list = [];
        Bucket.currentId = null;
        Bucket.loadList();

        Image.list = [];
        Image.listRequested = false;
    },

    view: function(vnode) {
        if (Bucket.currentId != null && !Image.listRequested) {
            Image.loadList(Bucket.currentId);
        }

        var loading = false;
        if (!Bucket.listLoaded || !Image.listLoaded) {
            loading = true;
        }

        var isTrash = Bucket.currentId == "trash";

        function checkImagesInView() {
            ImageGrid.checkImagesInViewPending = false;

            var scrollTop = document.documentElement.scrollTop;
            var scrollBottom = scrollTop + window.innerHeight;

            // Loop over each image in the DOM and check if it is visible.
            var inView = [];
            var images = document.getElementById("images");
            for (var i = 0; i < images.children.length; i++) {
                var image = images.children[i];
                var r = image.getBoundingClientRect();
                var imageTop = r.top + document.documentElement.scrollTop;
                var imageBottom = r.bottom + document.documentElement.scrollTop;

                if (scrollTop-ImageGrid.imageSize*2 <= imageTop && imageBottom <= scrollBottom+ImageGrid.imageSize*2) {
                    inView.push(image.attributes["data-id"].value);
                }
            }

            // Update the model Image.list to indicate which images are visible.
            var changed = false;
            for (var i = 0; i < Image.list.length; i++) {
                var shouldBeVisible = false;
                for (var j = 0; j < inView.length; j++) {
                    if (Image.list[i].imageId == inView[j]) {
                        shouldBeVisible = true;
                        break;
                    }
                }

                if (Image.list[i].visible != shouldBeVisible) {
                    Image.list[i].visible = shouldBeVisible;
                    changed = true;
                }
            }

            if (changed) {
                m.redraw();
            }
        }

        function scrollHandler(e) {
            if (!ImageGrid.checkImagesInViewPending) {
                ImageGrid.checkImagesInViewPending = true;
                setTimeout(checkImagesInView, 400);
            }
        }

        setTimeout(scrollHandler, 1);
        window.addEventListener('scroll', scrollHandler, false);
        window.addEventListener('resize', scrollHandler, false);

        var moveToTrash = function() {
            var promises = [];
            for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
                promises.push(Image.delete(ImageGrid.selectedImageIDs[i], isTrash));
            }

            Promise.all(promises)
            .then(function(a) {
                Image.loadList(Bucket.currentId);
                ImageGrid.selectedImageIDs = [];
           });
        };

        var deletePermanently = function() {
            var r = confirm("Permanently delete the selected items?");
            if (r == true) {
                moveToTrash();
            }
        }

        var restoreFromTrash = function() {
            var promises = [];
            for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
                promises.push(Image.restore(ImageGrid.selectedImageIDs[i]));
            }

            Promise.all(promises)
            .then(function(a) {
                Image.loadList(Bucket.currentId);
                ImageGrid.selectedImageIDs = [];
           });
        };

        var viewTrash = function() {
            ImageGrid.previouslyViewedCollection = Bucket.currentId;
            Bucket.currentId = "trash";
            Image.loadList(Bucket.currentId);
        }

        var returnToPreviouslyViewedCollection = function() {
            Bucket.currentId = ImageGrid.previouslyViewedCollection;
            Image.loadList(Bucket.currentId);
        }

        var clearImgSrcs = function() {
            // Clear all src attributes of images. This is to
            // ensure no images with their old resolution are
            // still present while the images in the new
            // resolution are being downloaded.
            var images = document.getElementById("images");
            for (var i = 0; i < images.children.length; i++) {
                for (var j = 0; j < images.children[i].children.length; j++) {
                    images.children[i].children[j].src = "";
                }
            }
        };

        return [
            m(".topbarspacer"),

            /*
             * Main image grid.
             */
            m("#images", {
                onclick: function(e) {
                    if (e.target.id == "images") {
                        ImageGrid.selectedImageIDs = [];
                    }
                }},
                Image.list.map(function(image) {
                    var selected = "";
                    for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
                        if (ImageGrid.selectedImageIDs[i] == image.imageId) {
                            selected = ".selected";
                        }
                    }

                    var selectImage = null;
                    if (image.visible) {
                        selectImage = function(e) {
                            var alreadySelected = false;
                            for (var i = 0; i < ImageGrid.selectedImageIDs.length; i++) {
                                if (ImageGrid.selectedImageIDs[i] == image.imageId) {
                                    alreadySelected = true;
                                    ImageGrid.selectedImageIDs.splice(i, 1);
                                    break;
                                }
                            }

                            if (!alreadySelected) {
                                ImageGrid.selectedImageIDs.push(image.imageId);
                            }
                        };
                    }

                    return m("div.image" + selected, {
                        "data-id": image.imageId,
                        style: "width: " + ImageGrid.imageSize + "px; " + "height: " + ImageGrid.imageSize + "px;",
                        onmousedown: selectImage
                    }, [
                        // Actual <img>.
                        image.visible ? [
                            m("img", {src: apiUrl + "/images/" + (isTrash ? "trash/" : "") + image.imageId + "?size=" + ImageGrid.imageSize + "&quality=" + ImageGrid.imageQuality})
                        ] : "",

                        m(".imglabel", image.name)
                    ]);
                })
            ),

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
                                Bucket.currentId = e.target.value;
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
                                clearImgSrcs();
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
                m(".control", [
                    m("label[for=quality]", "Quality"),
                    " ",
                    m("select#quality",
                        {
                            onchange: function(e) {
                                ImageGrid.imageQuality = e.target.value;
                                clearImgSrcs();
                            }                        },
                        [
                            m("option", {value: "high"}, "High (slow)"),
                            m("option", {value: "low"}, "Low (fast)")
                        ]
                    )
                ]),
                !isTrash ? [
                    m(".control", [
                        m("span.fa.fa-trash-o", { onclick: moveToTrash } ),
                        " ",
                        m("a", { onclick: moveToTrash }, "Move to trash")
                    ]),
                    m(".control", [
                        m("a", { onclick: viewTrash }, "View trash")
                    ])
                ] : [
                    m(".control", [
                        m("span.fa.fa-reply", { onclick: restoreFromTrash } ),
                        " ",
                        m("a", { onclick: restoreFromTrash }, "Restore from trash")
                    ]),
                    m(".control", [
                        m("span.fa.fa-times", { onclick: deletePermanently }
                        ),
                        " ",
                        m("a", { onclick: deletePermanently }, "Delete permanently")
                    ]),
                    m(".control", [
                        m("span.fa.fa-arrow-left", { onclick: returnToPreviouslyViewedCollection }
                        ),
                        " ",
                        m("a", { onclick: returnToPreviouslyViewedCollection }, "Return")
                    ])
                ]
            ]),

            // This needs to be last, to overlay everything else.
            loading ? m(".spinneroverlay", m(".spinner")) : null
        ];
    }
}

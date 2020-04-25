var ImageGrid = {

    checkImagesInViewPending: false,
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

        var images = Image.list.slice(ImageGrid.imagesBoxesToRenderStart, ImageGrid.imagesBoxesToRenderEnd);

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
                },
                onupdate: function() {
                    ImageGrid.scrollHandler();
                }},
                images.map(function(image) {
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
                        "id": "img" + image.imageId,
                        "data-id": image.imageId,
                        style: "width: " + ImageGrid.imageSize + "px; " + "height: " + ImageGrid.imageSize + "px;",
                        onmousedown: selectImage
                    }, [
                        // Actual <img>.
                        image.visible ? [
                            m("img", {src: apiUrl + "/images/" + (isTrash ? "trash/" : "") + image.imageId + "?size=" + ImageGrid.imageSize}),
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
                                Image.list = [];
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
                        m("a", { onclick: ImageGrid.deleteSelected }, "Move to trash")
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
                        m("a", { onclick: ImageGrid.deleteSelected }, "Delete permanently")
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

    checkImagesInView: function () {
        ImageGrid.checkImagesInViewPending = false;

        var changed = false;

        var scrollTop = document.documentElement.scrollTop;
        var scrollBottom = scrollTop + window.innerHeight;

        // Loop over each image div in the DOM and check if it is visible.
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

        // Decide if we are scrolled down long enough for us to add more image div's.
        var de = document.documentElement;
        var db = document.body;
        var docHeight = Math.max(db.scrollHeight, de.scrollHeight, db.offsetHeight, de.offsetHeight, db.clientHeight, de.clientHeight); // https://j11y.io/snippets/get-document-height-cross-browser/
        if (scrollBottom > docHeight - window.innerHeight && Image.list.length > ImageGrid.imagesBoxesToRenderEnd) {
            ImageGrid.imagesBoxesToRenderEnd += inView.length;
            changed = true;
        }

        if (changed) {
            m.redraw();
        }
    },

    scrollHandler: function() {
        if (!ImageGrid.checkImagesInViewPending) {
            ImageGrid.checkImagesInViewPending = true;
            setTimeout(ImageGrid.checkImagesInView, 400);
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
        Image.loadList(Bucket.currentId);
    },

    returnToPreviouslyViewedCollection: function() {
        Bucket.currentId = ImageGrid.previouslyViewedCollection;
        Image.loadList(Bucket.currentId);
    }

}

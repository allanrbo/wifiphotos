var ImageGrid = {

    checkImagesInViewPending: false,
    imageSize: 500,

    oninit: function() {
        Bucket.list = [];
        Bucket.currentId = null;
        Bucket.loadList();

        Image.list = [];
        Image.listRequested = false;
    },

    view: function() {
        if (Bucket.currentId != null && !Image.listRequested) {
            Image.loadList(Bucket.currentId);
        }

        var loading = false;
        if (!Bucket.listLoaded || !Image.listLoaded) {
            loading = true;
        }

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
            for (var i = 0; i < Image.list.length; i++) {
                Image.list[i].visible = false;
            }
            for (var i = 0; i < Image.list.length; i++) {
                for (var j = 0; j < inView.length; j++) {
                    if (Image.list[i].imageId == inView[j]) {
                        Image.list[i].visible = true;
                    }
                }
            }

            m.redraw();
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

        return [
            loading ? m(".spinneroverlay", m(".spinner")) : null,

            m(".topbarspacer"),

            m("#images", 
                Image.list.map(function(image) {
                    return m("div.image", {
                        "data-id": image.imageId,
                        style: "width: " + ImageGrid.imageSize + "px; " + "height: " + ImageGrid.imageSize + "px;"
                    }, [
                        //image.imageId,
                        image.visible ? [
                            m("img", {src: apiUrl + "/images/" + image.imageId + "?size=" + (ImageGrid.imageSize)})
                            // m("img", {style: "width: " + ImageGrid.imageSize + "px; height: " + ImageGrid.imageSize + "px;", src: "data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAZABkAAD/7AARRHVja3kAAQAEAAAACgAA/+4ADkFkb2JlAGTAAAAAAf/bAIQAFBAQGRIZJxcXJzImHyYyLiYmJiYuPjU1NTU1PkRBQUFBQUFERERERERERERERERERERERERERERERERERERERAEVGRkgHCAmGBgmNiYgJjZENisrNkREREI1QkRERERERERERERERERERERERERERERERERERERERERERERERERE/8AAEQgABAAFAwEiAAIRAQMRAf/EAFgAAQEAAAAAAAAAAAAAAAAAAAAEAQEAAAAAAAAAAAAAAAAAAAADEAABAwUBAAAAAAAAAAAAAAAAARESMWFxobEkEQADAQEAAAAAAAAAAAAAAAAA8EFhwf/aAAwDAQACEQMRAD8Ai801rN0xJube4ACteCrp/9k="})
                        ] : ""
                    ]);
                })
            ),

            m(".topbar", [
                m("label[for=collection]", "Collection"),
                " ",
                m("select#collection",
                    {
                        onchange: function(e) { Bucket.currentId = e.target.value; Image.loadList(Bucket.currentId); },
                    },
                    Bucket.list.map(function(bucket) {
                        var selected = "";
                        if (Bucket.currentId == bucket.id) {
                            selected = "selected";
                        }
                        return m("option", {value: bucket.id, selected: selected}, bucket.displayName);
                    })
                ),
                " ",
                m("label[for=size]", "Size"),
                " ",
                m("select#size",
                    {
                        onchange: function(e) {
                            ImageGrid.imageSize = e.target.value;

                            // Clear all src attributes of images. This is to
                            // ensure no images with their old resolution are
                            // still present while the images in the new
                            // resolution is downloded.
                            var images = document.getElementById("images");
                            for (var i = 0; i < images.children.length; i++) {
                                for (var j = 0; j < images.children[i].children.length; j++) {
                                    images.children[i].children[j].src = "";
                                }
                            }
                        },
                    },
                    [100,200,300,400,500,600,700,800,900,1000].map(function(s) {
                        var selected = "";
                        if (ImageGrid.imageSize == s) {
                            selected = "selected";
                        }
                        return m("option", {value: s, selected: selected}, s);
                    })
                ),
                " "
            ])
        ];
    }
}

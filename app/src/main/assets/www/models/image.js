var Image = {
    list: [],
    listLoaded: false,

    loadList: function(bucketId) {
        Image.listLoaded = false;
        return m.request({
            method: "GET",
            url: apiUrl + "/buckets/" + bucketId,
        })
        .then(function(result) {
            Image.list = result;
            Image.listLoaded = true;
        })
        .catch(function(e) {
            Image.listLoaded = true;
            throw e;
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    },

    delete: function(imageId, isTrash) {
        var url = apiUrl + "/images/" + imageId;
        if (isTrash) {
            url = apiUrl + "/images/trash/" + imageId;
        }

        return m.request({
            method: "DELETE",
            url: url
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    },

    restore: function(imageId) {
        return m.request({
            method: "POST",
            url: apiUrl + "/images/trash/" + imageId,
            body: { "action": "restore" }
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    },

    calcNewDimensions: function(srcWidth, srcHeight, dstSize) {
        if (srcWidth == 0 || srcHeight == 0) {
            return {width: srcWidth, height: srcHeight};
        }

        var dstWidth;
        var dstHeight;
        if (srcWidth > srcHeight) {
            if (srcWidth > dstSize) {
                dstWidth = dstSize;
                dstHeight = Math.floor((srcHeight * dstSize) / srcWidth);
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        } else {
            if (srcHeight > dstSize) {
                dstWidth = Math.floor((srcWidth * dstSize) / srcHeight);
                dstHeight = dstSize;
            } else {
                dstWidth = srcWidth;
                dstHeight = srcHeight;
            }
        }

        return {width: dstWidth, height: dstHeight};
    }
}

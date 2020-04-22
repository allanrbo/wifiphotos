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
        .catch(alertErrorMessage);
    },

    delete: function(imageId, isTrash) {
        var url = apiUrl + "/images/" + imageId;
        if (isTrash) {
            url = apiUrl + "/images/trash/" + imageId;
        }

        return m.request({
            method: "DELETE",
            url: url,
            config: xhrConfig
        })
        .catch(alertErrorMessage);
    },

    restore: function(imageId) {
        return m.request({
            method: "POST",
            url: apiUrl + "/images/trash/" + imageId,
            body: { "action": "restore" },
            config: xhrConfig
        })
        .catch(alertErrorMessage);
    }
}

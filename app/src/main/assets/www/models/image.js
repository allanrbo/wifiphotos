var Image = {
    list: [],
    listLoaded: false,
    listRequested: false,

    loadList: function(bucketId) {
        Image.listLoaded = false;
        Image.listRequested = true;
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
    }
}

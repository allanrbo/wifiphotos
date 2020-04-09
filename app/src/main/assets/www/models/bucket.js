var Bucket = {
    list: [],
    listLoaded: false,
    currentId: null,

    loadList: function() {
        Bucket.listLoaded = false;
        return m.request({
            method: "GET",
            url: apiUrl + "/buckets",
        })
        .then(function(result) {
            Bucket.list = result;

            if (Bucket.currentId == null) {
                for (var i = 0; i < Bucket.list.length; i++) {
                    if (Bucket.list[i].isCameraBucket) {
                        Bucket.currentId = Bucket.list[i].id;
                    }
                }
            }

            Bucket.listLoaded = true;
        })
        .catch(function(e) {
            Bucket.listLoaded = true;
            throw e;
        })
        .catch(alertErrorMessage);
    }
}

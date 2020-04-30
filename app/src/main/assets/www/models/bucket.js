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
                Bucket.currentId = Bucket.list[0].id;
                for (var i = 0; i < Bucket.list.length; i++) {
                    if (Bucket.list[i].isCameraBucket) {
                        Bucket.currentId = Bucket.list[i].id;
                        break;
                    }
                }
            }

            Bucket.listLoaded = true;
        })
        .catch(function(e) {
            Bucket.listLoaded = true;
            throw e;
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    }
}

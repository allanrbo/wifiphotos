var Ping = {
    lostConnection: false,

    ping: function() {
        return m.request({
            method: "GET",
            url: apiUrl + "/ping",
            config: xhrConfig
        })
        .then(function() {
            Ping.lostConnection = false;
        })
        .catch(function(e) {
            if (e.code == null || e.code == 0) {
                Ping.lostConnection = true;
            }
            throw e;
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    }
}

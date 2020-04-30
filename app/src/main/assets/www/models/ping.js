var Ping = {
    ping: function() {
        return m.request({
            method: "GET",
            url: apiUrl + "/ping",
            config: xhrConfig
        })
        .catch(handleUnauthorized)
        .catch(alertErrorMessage);
    }
}

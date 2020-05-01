var xhrConfig = function(xhr) {
    xhr.setRequestHeader("Content-Type", "application/json");
};

var handleUnauthorized = function(e) {
    if(e.response != null && e.response.status == "unauthorized") {
        m.route.set('/login');
        e.response.message = null;
    } else {
        throw e;
    }
};

var alertErrorMessage = function(e) {
    if (e == null || e.code == null || e.code == 0) {
        Ping.lostConnection = true;
    } else if (e.response == null || e.message == "null") {
        alert("An internal error occurred");
    } else if (e.response && e.response.message) {
        alert(e.response.message);
    } else if(e.message) {
        alert(e.message);
    }
    throw e;
};

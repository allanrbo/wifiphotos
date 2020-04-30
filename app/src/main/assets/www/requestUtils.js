var xhrConfig = function(xhr) {
    xhr.setRequestHeader("Content-Type", "application/json");
};

var handleUnauthorized = function(e) {
    if(e.response.status == "unauthorized") {
        m.route.set('/login');
        e.response.message = null;
    }
};

var alertErrorMessage = function(e) {
    if (e.code == null || e.code == 0) {
        alert("Lost connection to device. Check that the app is still open.");
    } else if (e.response == null || e.message == "null") {
        alert("An internal error occurred");
    } else if (e.response && e.response.message) {
        alert(e.response.message);
    } else if(e.message) {
        alert(e.message);
    }
    throw e;
};

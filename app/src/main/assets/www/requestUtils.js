var xhrConfig = function(xhr) {
    xhr.setRequestHeader("Content-Type", "application/json");
};

var alertErrorMessage = function(e) {
    if (e.code == 0) {
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

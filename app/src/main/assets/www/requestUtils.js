var xhrConfig = function(xhr) {
    xhr.setRequestHeader("Content-Type", "application/json");
};

var alertErrorMessage = function(e) {
    if(e.message) {
        alert(e.message);
    }
    throw e;
};

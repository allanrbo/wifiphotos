var Login = {
    loginDenied: false,
    pin: 0,
    token: "",

    resetPinAndToken: function() {
        function randomInteger(min, max) {
            return Math.floor(Math.random() * (max - min + 1)) + min;
        }

        // https://stackoverflow.com/a/1349426/40645
        function randomString(length) {
           var result           = '';
           var characters       = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
           var charactersLength = characters.length;
           for ( var i = 0; i < length; i++ ) {
              result += characters.charAt(Math.floor(Math.random() * charactersLength));
           }
           return result;
        }

        Login.loginDenied = false;
        Login.pin = randomInteger(1000,9999);
        Login.token = randomString(50);
    },

    blockingLogin: function(imageId) {
        return m.request({
            method: "POST",
            url: apiUrl + "/login",
            body: { "pin": Login.pin, "token": Login.token },
            config: xhrConfig
        })
        .catch(alertErrorMessage)
        .then(function(e) {
            Login.loginDenied = true;
            if (e.status == "allow") {
                Login.loginDenied = false;
            }

            Ping.lostConnection = false;
       });
    }
}

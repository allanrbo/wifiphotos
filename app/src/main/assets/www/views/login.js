var LoginView = {
    oninit: function() {
        LoginView.login();
    },

    view: function(vnode) {
        if (Login.loginDenied) {
            return m(".login", [
                m("div", "Access denied."),
                m("button", {
                    onclick: function() {
                        LoginView.login();
                    }
                }, "Retry")
            ]);
        }

        return m(".login", [
            m(".pin", "PIN: " + Login.pin),
            "Press allow on your device to proceed."
        ]);
    },

    login: function() {
        Login.resetPinAndToken();
        Login.blockingLogin()
        .catch(function(e) {
            Ping.ping();
        })
        .then(function(e) {
            if (!Login.loginDenied) {
                ImageGrid.shouldScrollToTimestamp = true;
                m.route.set("/");
            }
       });
    }
}

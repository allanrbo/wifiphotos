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
        .then(function(e) {
            if (!Login.loginDenied) {
                m.route.set("/");
            }
       });
    }
}

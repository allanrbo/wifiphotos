var apiUrl = "/api";

m.route(
    document.body,
    "/", {
        "/": { render: function() { return m(ImageGrid); } },
        "/login": { render: function() { return m(LoginView); } },
    }
);

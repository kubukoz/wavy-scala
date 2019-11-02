if (process.env.NODE_ENV === "production") {
    const opt = require("./wavy-scala-opt.js");
    opt.main();
    module.exports = opt;
} else {
    var exports = window;
    exports.require = require("./wavy-scala-fastopt-entrypoint.js").require;
    window.global = window;

    const fastOpt = require("./wavy-scala-fastopt.js");
    fastOpt.main()
    module.exports = fastOpt;

    if (module.hot) {
        module.hot.accept();
    }
}

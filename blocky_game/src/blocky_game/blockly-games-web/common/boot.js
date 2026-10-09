
// Single-language bootloader.
(function() {
  // Application path.
  var appName = location.pathname.match(/\/([-\w]+)(\.html)?$/);
  appName = appName ? appName[1].replace('-', '/') : 'index';
  if (appName === 'maze/original' || appName === 'maze/classic' || appName === 'maze_original' || appName === 'original/maze') {
    appName = 'maze';
  }

  // Only one language.
  var lang = 'en';
  window['BlocklyGamesLanguages'] = [lang];
  window['BlocklyGamesLang'] = lang;

  // Load the language pack.
  var script = document.createElement('script');
  script.src = appName + '/generated/' + lang + '/compressed.js';
  script.type = 'text/javascript';
  document.head.appendChild(script);
})();

Object.defineProperty(window, 'matchMedia', { writable: true, value: query => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return false; } }) });
global.IS_REACT_ACT_ENVIRONMENT = true;
// jsdom has no layout engine; chart/canvas rendering is checked in the real browser.
const originalGetComputedStyle = window.getComputedStyle;
window.getComputedStyle = element => originalGetComputedStyle(element);

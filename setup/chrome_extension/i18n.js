const TTS_LANGS = {
  en: "English",
  de: "Deutsch",
  es: "Español",
  fr: "Français",
  pt: "Português",
  tr: "Türkçe",
};

async function ttsLoadTexts() {
  let chosen = null;
  try {
    chosen = (await chrome.storage.local.get("lang")).lang;
  } catch {
    chosen = null;
  }
  const browser = (chrome.i18n.getUILanguage() || "en").slice(0, 2).toLowerCase();
  const code = chosen && chosen in TTS_LANGS ? chosen : (browser in TTS_LANGS ? browser : "en");
  let messages = {};
  try {
    const file = chrome.runtime.getURL(`_locales/${code}/messages.json`);
    messages = await (await fetch(file)).json();
  } catch {
    messages = {};
  }
  return {
    code,
    text: (key) => messages[key]?.message || chrome.i18n.getMessage(key) || key,
  };
}

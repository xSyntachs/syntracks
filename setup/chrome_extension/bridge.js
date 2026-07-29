const ITEM = 'article[data-e2e="recommend-list-item-container"]';

function videoIdOf(article) {
  const fiberKey = Object.keys(article).find((k) => k.startsWith("__reactFiber"));
  let node = fiberKey && article[fiberKey];
  for (let step = 0; step < 10 && node; step++, node = node.return) {
    const id = node.memoizedProps?.id;
    if (typeof id === "string" && /^\d{18,20}$/.test(id)) return id;
  }
  return null;
}

function tagItems() {
  for (const article of document.querySelectorAll(ITEM)) {
    const id = videoIdOf(article);
    if (id && article.dataset.ttsId !== id) article.dataset.ttsId = id;
  }
}

let tagPending = false;
new MutationObserver(() => {
  if (tagPending) return;
  tagPending = true;
  requestAnimationFrame(() => { tagPending = false; tagItems(); });
}).observe(document.body, { childList: true, subtree: true });
tagItems();

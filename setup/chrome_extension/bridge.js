(() => {
  if (window.__ttsBridge) return;
  window.__ttsBridge = true;

  const ITEMS = 'article, [data-e2e="recommend-list-item-container"], [data-e2e="feed-video"]';

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
    let current = null;
    let mostVisible = 0;
    for (const article of document.querySelectorAll(ITEMS)) {
      const id = videoIdOf(article);
      if (!id) continue;
      if (article.dataset.ttsId !== id) article.dataset.ttsId = id;
      const box = article.getBoundingClientRect();
      const visible = Math.max(0, Math.min(box.bottom, innerHeight) - Math.max(box.top, 0));
      if (visible > mostVisible) {
        mostVisible = visible;
        current = id;
      }
    }
    if (current) document.body.dataset.ttsCurrent = current;
  }

  let tagPending = false;
  new MutationObserver(() => {
    if (tagPending) return;
    tagPending = true;
    requestAnimationFrame(() => { tagPending = false; tagItems(); });
  }).observe(document.body, { childList: true, subtree: true });
  tagItems();
})();

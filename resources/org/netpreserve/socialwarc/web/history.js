document.querySelector("#back-link")?.addEventListener("click", event => {
  event.preventDefault();
  history.back();
});

const currentPost = document.querySelector("#current-post");
if (currentPost) {
  window.addEventListener("pageshow", () =>
    setTimeout(() => currentPost.scrollIntoView({block: "center"}), 0),
    {once: true});
}

const timeline = document.querySelector(".timeline");

if (timeline) {
  const svg = timeline.querySelector("svg");
  const target = timeline.querySelector(".timeline-target");
  const selection = timeline.querySelector(".timeline-selection");
  const output = timeline.querySelector("output");
  const clear = timeline.querySelector(".timeline-clear");
  const form = document.querySelector("form.search");
  const since = form.querySelector("#since");
  const until = form.querySelector("#until");
  const buckets = [...timeline.querySelectorAll(".timeline-bar")];
  const chartWidth = 600;
  let anchor = null;

  const bucketAt = event => {
    const bounds = svg.getBoundingClientRect();
    const x = Math.max(0, Math.min(bounds.width, event.clientX - bounds.left));
    return Math.min(buckets.length - 1,
      Math.floor(x / bounds.width * buckets.length));
  };

  const draw = (first, last, start, end) => {
    const left = Math.min(first, last);
    const right = Math.max(first, last);
    const width = chartWidth / buckets.length;
    selection.removeAttribute("hidden");
    selection.setAttribute("x", String(left * width));
    selection.setAttribute("width", String((right - left + 1) * width));
    buckets.forEach((bucket, index) =>
      bucket.classList.toggle("selected", index >= left && index <= right));
    output.textContent = `${start} – ${end}`;
  };

  const drawInputs = () => {
    if (!since.value && !until.value) {
      selection.setAttribute("hidden", "");
      buckets.forEach(bucket => bucket.classList.remove("selected"));
      output.textContent = "All dates";
      return;
    }
    const start = since.value || timeline.dataset.start;
    const end = until.value || timeline.dataset.end;
    const first = Math.max(0, buckets.findIndex(bucket =>
      bucket.dataset.end >= start));
    let last = buckets.findLastIndex(bucket => bucket.dataset.start <= end);
    if (last < 0) last = buckets.length - 1;
    draw(first, last, start, end);
  };

  target.addEventListener("pointerdown", event => {
    event.preventDefault();
    anchor = bucketAt(event);
    target.setPointerCapture(event.pointerId);
    const bucket = buckets[anchor];
    draw(anchor, anchor, bucket.dataset.start, bucket.dataset.end);
  });

  target.addEventListener("pointermove", event => {
    if (anchor === null) return;
    const current = bucketAt(event);
    const first = Math.min(anchor, current);
    const last = Math.max(anchor, current);
    draw(first, last,
      buckets[first].dataset.start, buckets[last].dataset.end);
  });

  target.addEventListener("pointerup", event => {
    if (anchor === null) return;
    const current = bucketAt(event);
    const first = Math.min(anchor, current);
    const last = Math.max(anchor, current);
    since.value = buckets[first].dataset.start;
    until.value = buckets[last].dataset.end;
    anchor = null;
    form.requestSubmit();
  });

  target.addEventListener("pointercancel", () => {
    anchor = null;
    drawInputs();
  });

  clear.addEventListener("click", () => {
    since.value = "";
    until.value = "";
    form.requestSubmit();
  });

  since.addEventListener("input", drawInputs);
  until.addEventListener("input", drawInputs);
  drawInputs();
}

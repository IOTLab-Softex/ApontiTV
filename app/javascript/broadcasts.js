function initializeLegacyBroadcastCards() {
  const broadcastCard = document.querySelector(".broadcast-card-desktop");
  const cards = document.querySelectorAll(".broadcast-card");
  const editMenu = document.getElementById("edit-menu");
  const editLink = document.getElementById("edit-link");
  const deleteLink = document.getElementById("delete-link");

  if (!broadcastCard && cards.length === 0) return;

  if (broadcastCard) {
    const timestamp = new Date().getTime();
    broadcastCard.style.backgroundImage = `url(/wallpapers/wallpaper.jpeg?${timestamp})`;
  }

  cards.forEach((card) => {
    if (card.dataset.legacyBroadcastBound === "true") return;
    card.dataset.legacyBroadcastBound = "true";

    card.addEventListener("click", (event) => {
      if (!editMenu || !editLink || !deleteLink) return;

      cards.forEach((item) => {
        item.classList.remove("selected");
        item.classList.add("mirror-effect");
      });

      card.classList.add("selected");
      card.classList.remove("mirror-effect");

      const broadcastId = card.getAttribute("data-broadcast-id");
      editMenu.classList.remove("hidden");
      editLink.href = `/broadcasts/${broadcastId}/edit`;
      deleteLink.href = `/broadcasts/${broadcastId}`;

      const cardRect = card.getBoundingClientRect();
      editMenu.style.top = `${cardRect.top + window.scrollY}px`;
      editMenu.style.left = `${cardRect.right + window.scrollX + 100}px`;
      event.stopPropagation();
    });
  });

  if (editMenu && editMenu.dataset.legacyCloseBound !== "true") {
    editMenu.dataset.legacyCloseBound = "true";
    document.addEventListener("click", (event) => {
      if (!editMenu.contains(event.target)) {
        editMenu.classList.add("hidden");
        cards.forEach((item) => {
          item.classList.remove("selected");
          item.classList.add("mirror-effect");
        });
      }
    });
  }
}

document.addEventListener("DOMContentLoaded", initializeLegacyBroadcastCards);
document.addEventListener("turbo:load", initializeLegacyBroadcastCards);

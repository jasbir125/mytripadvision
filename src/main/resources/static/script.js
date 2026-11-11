// MyTripAdvision Agent: the page script. Plain JavaScript, no framework.
//
// The page shows a conversation. We keep it in one list called `messages` and draw the whole list again
// whenever it changes (see render()). That keeps the code short and easy to follow.

const APP_NAME = "MyTripAdvision Agent";

// Anonymous id for this browser. It keeps your trips separate from other people's.
const userId = localStorage.getItem("travel_user_id") || (() => {
    const id = crypto.randomUUID();
    localStorage.setItem("travel_user_id", id);
    return id;
})();

let currentThreadId = localStorage.getItem("travel_thread_id") || null;
let messages = [];            // what is on screen: { role: "user"|"assistant", content, status?, agent?, notices?, stats? }
let waitingForReview = false; // true while the last answer is a draft the user has not approved yet
let workingText = "";         // text of the "working..." bubble; empty when nothing is running


// ---------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------

function byId(id) {
    return document.getElementById(id);
}

function escapeHtml(text) {
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// Markdown to safe HTML. The text comes from an AI model and from web search results, so it is never
// trusted: it goes through DOMPurify. If the libraries did not load, we show plain text instead.
function toHtml(markdown) {
    if (typeof marked !== "undefined" && typeof DOMPurify !== "undefined") {
        return wrapTables(DOMPurify.sanitize(marked.parse(markdown)));
    }
    return escapeHtml(markdown).replace(/\n/g, "<br>");
}

// Put every table in a box that scrolls sideways on a small screen (the table itself keeps its full width).
function wrapTables(html) {
    const holder = document.createElement("div");
    holder.innerHTML = html;
    holder.querySelectorAll("table").forEach(table => {
        const wrap = document.createElement("div");
        wrap.className = "table-wrap";
        table.replaceWith(wrap);
        wrap.appendChild(table);
    });
    return holder.innerHTML;
}

function setPrompt(text) {
    const input = byId("userInput");
    input.value = text;
    growInput();
    input.focus();
}

// The input box grows with the text, up to a limit set in the CSS.
function growInput() {
    const input = byId("userInput");
    input.style.height = "auto";
    input.style.height = input.scrollHeight + "px";
}

function showError(message) {
    const box = byId("errorBox");
    box.textContent = message;
    box.classList.remove("hidden");
}

function hideError() {
    const box = byId("errorBox");
    box.classList.add("hidden");
    box.textContent = "";
}

function setLoading(isLoading) {
    byId("sendBtn").disabled = isLoading;
    byId("btnText").classList.toggle("hidden", isLoading);
    byId("btnLoader").classList.toggle("hidden", !isLoading);
    document.querySelectorAll(".review .btn").forEach(button => button.disabled = isLoading);
}

function toggleSidebar(open) {
    byId("sidebar").classList.toggle("open", open);
    byId("scrim").classList.toggle("show", open);
}

function scrollToBottom() {
    const conversation = byId("conversation");
    conversation.scrollTop = conversation.scrollHeight;
}

// Show the start of the newest answer, so the user reads a long plan from the top.
function scrollToLatestAnswer() {
    const cards = document.querySelectorAll("#messages .msg.assistant");
    if (cards.length) {
        cards[cards.length - 1].scrollIntoView({ block: "start" });
    }
}


// ---------------------------------------------------------------
// Drawing the conversation
// ---------------------------------------------------------------

function render() {
    const list = byId("messages");
    list.innerHTML = "";

    const lastAnswerIndex = messages.map(m => m.role).lastIndexOf("assistant");
    messages.forEach((message, index) => list.appendChild(messageElement(message, index, index === lastAnswerIndex)));
    if (workingText) {
        list.appendChild(workingElement(workingText));
    }

    byId("emptyState").classList.toggle("hidden", messages.length > 0 || Boolean(workingText));
    byId("reviewBox").classList.toggle("hidden", !waitingForReview || Boolean(workingText));

    // the title bar
    const firstQuestion = messages.find(m => m.role === "user");
    byId("tripTitle").textContent = firstQuestion ? firstQuestion.content.slice(0, 90) : "New trip";
    byId("threadInfo").textContent = currentThreadId && messages.length
        ? "Trip " + currentThreadId.replace("user_", "").slice(0, 8)
        : "Tell me where you want to go";

    highlightActiveThread();
}

// A short answer (a question, a refusal) is a small card. A long one with headings is a "trip plan" card.
function isPlan(message) {
    return message.content.length > 500 && /^#{1,3} /m.test(message.content);
}

// The name of the agent that wrote the answer, as shown on the card.
const AGENT_NAMES = {
    guardrails: "Guardrails agent",
    supervisor: "Supervisor agent",
    itinerary: "Itinerary agent",
    final: "Final agent"
};

// Answers saved before the agent was recorded have no "agent": work it out from what is known.
function agentName(message) {
    let agent = message.agent;
    if (!agent) {
        if (message.status === "needs_review") {
            agent = "itinerary";
        } else if (isPlan(message)) {
            agent = "final";
        } else {
            agent = "supervisor";
        }
    }
    return AGENT_NAMES[agent] || agent + " agent";
}

// One line per service that was down or out of quota while the answer was made.
function noticeElements(message) {
    const lines = [];
    for (const text of message.notices || []) {
        const line = document.createElement("div");
        line.className = "notice";
        line.textContent = "⚠ " + text;
        lines.push(line);
    }
    return lines;
}

function messageElement(message, index, isLatestAnswer) {
    const row = document.createElement("div");
    row.className = "msg " + message.role;

    if (message.role === "user") {
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = message.content;   // textContent: the user's own text is never treated as HTML
        row.appendChild(bubble);
        return row;
    }

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = "✦";

    const plan = isPlan(message);
    const card = document.createElement("div");
    card.className = "card" + (plan ? "" : " simple");

    if (plan) {
        card.appendChild(cardHeader(message, isLatestAnswer));
    } else {
        const from = document.createElement("div");
        from.className = "card-from";
        from.textContent = agentName(message);
        card.appendChild(from);
    }
    noticeElements(message).forEach(line => card.appendChild(line));

    const body = document.createElement("div");
    body.className = "card-body md";
    body.innerHTML = toHtml(message.content);
    card.appendChild(body);

    if (plan) {
        card.appendChild(cardActions(index));
    }

    row.append(avatar, card);
    return row;
}

// The row of small labels above a plan: who wrote it, draft or final, which agents ran, tokens and cost.
function cardHeader(message, isLatestAnswer) {
    const head = document.createElement("div");
    head.className = "card-head";
    head.appendChild(chip(agentName(message), "agent"));

    if (message.status === "needs_review" && !isLatestAnswer) {
        head.appendChild(chip("Earlier draft", "muted"));      // a newer answer has replaced it
    } else if (message.status === "needs_review") {
        head.appendChild(chip("Draft: waiting for your review", "warn"));
    } else if (message.status === "done") {
        // a whole trip is a "Final plan"; an answer about one thing (the weather, hotels...) is just an "Answer"
        const hasItinerary = message.stats ? message.stats.agents.includes("itinerary") : /Day\s*1\b/i.test(message.content);
        head.appendChild(chip(hasItinerary ? "Final plan" : "Answer", "ok"));
    } else {
        head.appendChild(chip("Trip plan", ""));
    }

    if (message.stats) {
        head.appendChild(chip(message.stats.agents.join(" · ") || "no agents", "muted"));
        head.appendChild(chip(message.stats.tokens + " tokens · $" + message.stats.cost, "muted"));
    }
    return head;
}

function chip(text, kind) {
    const element = document.createElement("span");
    element.className = "chip " + kind;
    element.textContent = text;
    return element;
}

function cardActions(index) {
    const actions = document.createElement("div");
    actions.className = "card-actions";

    const copy = document.createElement("button");
    copy.className = "btn btn-ghost";
    copy.textContent = "Copy";
    copy.onclick = () => copyAnswer(index, copy);

    const pdf = document.createElement("button");
    pdf.className = "btn btn-ghost";
    pdf.textContent = "Download PDF";
    pdf.onclick = () => downloadPDF(index, pdf);

    actions.append(copy, pdf);
    return actions;
}

function workingElement(text) {
    const row = document.createElement("div");
    row.className = "msg assistant";
    row.innerHTML = '<div class="avatar">✦</div>'
        + '<div class="typing"><span class="dots"><i></i><i></i><i></i></span><span></span></div>';
    row.querySelector(".typing span:last-child").textContent = text;
    return row;
}


// ---------------------------------------------------------------
// Talking to the server
// ---------------------------------------------------------------

// Sends one request and shows the answer. Returns true when it worked.
async function callServer(url, body, workingMessage) {
    hideError();
    workingText = workingMessage;
    setLoading(true);
    render();
    scrollToBottom();

    try {
        const response = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
        });
        const data = await response.json();

        if (!response.ok || !data.success) {
            throw new Error(data.error || "Something went wrong.");
        }
        handleResult(data);
        return true;

    } catch (error) {
        showError(error.message);
        return false;

    } finally {
        workingText = "";
        setLoading(false);
        render();
    }
}

async function sendMessage() {
    if (workingText) {
        return;
    }
    const input = byId("userInput");
    const text = input.value.trim();

    if (!text) {
        showError("Please enter your travel request first.");
        return;
    }

    messages.push({ role: "user", content: text });
    input.value = "";
    growInput();

    const worked = await callServer("/api/travel",
        { message: text, thread_id: currentThreadId, user_id: userId },
        "Planning your trip: checking flights, hotels and the weather…");

    if (!worked) {
        messages.pop();          // take the question back, so nothing is lost
        input.value = text;
        growInput();
        render();
    } else {
        scrollToLatestAnswer();
    }
}

// action is "approve" or "changes"
async function submitReview(action) {
    if (workingText) {
        return;
    }
    const feedback = byId("reviewFeedback").value.trim();

    if (action === "changes" && !feedback) {
        showError("Please describe what you want changed.");
        return;
    }

    messages.push({
        role: "user",
        content: action === "approve" ? "Approve this plan ✓" : "Please change: " + feedback
    });
    byId("reviewFeedback").value = "";

    const worked = await callServer("/api/travel/review",
        { thread_id: currentThreadId, user_id: userId, action: action, feedback: feedback },
        action === "approve" ? "Writing your final plan…" : "Updating your plan…");

    if (!worked) {
        messages.pop();
        byId("reviewFeedback").value = feedback;
        render();
    } else {
        scrollToLatestAnswer();
    }
}

// Show an answer from the server. A draft plan ("needs_review") also shows the review box.
function handleResult(data) {
    currentThreadId = data.thread_id;
    localStorage.setItem("travel_thread_id", currentThreadId);

    const usage = data.token_usage || {};
    messages.push({
        role: "assistant",
        content: data.answer,
        status: data.status,
        agent: data.agent,
        notices: data.notices || [],
        stats: { agents: data.plan || [], tokens: usage.total || 0, cost: usage.cost_usd || 0 }
    });
    waitingForReview = data.status === "needs_review";
    loadThreads();
}


// ---------------------------------------------------------------
// Copy and PDF
// ---------------------------------------------------------------

function copyAnswer(index, button) {
    navigator.clipboard.writeText(messages[index].content)
        .then(() => {
            button.textContent = "Copied!";
            setTimeout(() => button.textContent = "Copy", 1400);
        })
        .catch(() => showError("Could not copy the plan."));
}

function downloadPDF(index, button) {
    // A fresh page-sized sheet, not attached to the page. html2pdf makes its own copy of it, so it must NOT be
    // hidden or moved off screen (a copy of an off-screen element prints as a blank page).
    const firstQuestion = messages.find(m => m.role === "user");
    const sheet = document.createElement("div");
    sheet.className = "pdf-sheet";
    sheet.innerHTML =
        '<h1 class="pdf-title">' + escapeHtml(APP_NAME) + '</h1>'
        + '<p class="pdf-sub">' + escapeHtml(firstQuestion ? firstQuestion.content.slice(0, 120) : "") + '</p>'
        + '<div class="md">' + toHtml(messages[index].content) + '</div>';

    button.textContent = "Preparing PDF…";
    button.disabled = true;

    const options = {
        margin: 0.5,
        filename: "my-trip-plan.pdf",
        image: { type: "jpeg", quality: 0.98 },
        html2canvas: { scale: 2, useCORS: true, backgroundColor: "#ffffff" },
        jsPDF: { unit: "in", format: "a4", orientation: "portrait" },
        pagebreak: { mode: ["avoid-all", "css", "legacy"] }
    };

    html2pdf().set(options).from(sheet).save()
        .catch(() => showError("Could not download the PDF."))
        .finally(() => {
            button.textContent = "Download PDF";
            button.disabled = false;
        });
}


// ---------------------------------------------------------------
// Saved trips (the sidebar)
// ---------------------------------------------------------------

async function loadThreads() {
    const response = await fetch(`/api/threads?user_id=${userId}`);
    const data = await response.json();

    const list = byId("threadList");
    list.innerHTML = "";

    if (!data.threads.length) {
        const empty = document.createElement("p");
        empty.textContent = "No saved trips yet.";
        list.appendChild(empty);
        return;
    }

    data.threads.forEach(thread => {
        const row = document.createElement("div");
        row.className = "thread-row";
        row.dataset.threadId = thread.thread_id;

        const open = document.createElement("button");
        open.className = "thread-open";
        open.textContent = thread.title;
        open.title = thread.title;
        open.onclick = () => openThread(thread.thread_id);

        const remove = document.createElement("button");
        remove.className = "thread-delete";
        remove.textContent = "✕";
        remove.setAttribute("aria-label", "Delete this trip");
        remove.onclick = () => deleteThread(thread.thread_id);

        row.append(open, remove);
        list.appendChild(row);
    });
    highlightActiveThread();
}

function highlightActiveThread() {
    document.querySelectorAll(".thread-row").forEach(row =>
        row.classList.toggle("active", row.dataset.threadId === currentThreadId && messages.length > 0));
}

async function openThread(threadId) {
    hideError();
    const response = await fetch(`/api/threads/${threadId}/messages?user_id=${userId}`);
    const data = await response.json();

    // The trip is gone (deleted, or the database was reset): start fresh instead of keeping a dead id.
    if (!data.messages || !data.messages.length) {
        newChat();
        return;
    }

    currentThreadId = threadId;
    localStorage.setItem("travel_thread_id", threadId);

    // The whole conversation: your questions and the answers.
    messages = data.messages.map(m => ({
        role: m.role,
        content: m.content,
        status: m.meta ? m.meta.status : undefined,
        agent: m.meta ? m.meta.agent : undefined,
        notices: m.meta && m.meta.notices ? m.meta.notices : []
    }));

    // A plan that was still waiting for review can be approved or changed after reopening.
    const lastAnswer = messages.filter(m => m.role === "assistant").pop();
    waitingForReview = Boolean(lastAnswer && lastAnswer.status === "needs_review");

    render();
    toggleSidebar(false);
    byId("conversation").scrollTop = 0;
}

async function deleteThread(threadId) {
    await fetch(`/api/threads/${threadId}?user_id=${userId}`, { method: "DELETE" });

    if (threadId === currentThreadId) {
        newChat();
    }
    loadThreads();
}

function newChat() {
    currentThreadId = null;
    localStorage.removeItem("travel_thread_id");
    messages = [];
    waitingForReview = false;
    hideError();
    byId("userInput").value = "";
    growInput();
    render();
    toggleSidebar(false);
    byId("userInput").focus();
}


// ---------------------------------------------------------------
// Start-up
// ---------------------------------------------------------------

// Enter sends, Shift+Enter makes a new line (Ctrl+Enter also sends).
byId("userInput").addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        sendMessage();
    }
});
byId("userInput").addEventListener("input", growInput);

// The long hint does not fit on a phone.
if (window.matchMedia("(max-width: 900px)").matches) {
    byId("userInput").placeholder = "Describe your trip…";
}

// Reload the trip that was open before the page was refreshed.
if (currentThreadId) {
    openThread(currentThreadId).catch(() => newChat());
} else {
    render();
}
loadThreads();

// menu-loader.js
document.addEventListener('DOMContentLoaded', function() {
    // 1. Create the Menu HTML structure
    const menuContainer = document.createElement('div');
    menuContainer.innerHTML = `
    <button class="sidebar-trigger" id="drawerHook" onclick="toggleSidebarMenu()">MENU</button>
    <nav class="sidebar-drawer" id="sidebarDrawer">
        <h4>System Matrix</h4>
        <a href="/index.html">⚡ Main Base Root</a>
        <a href="/Software.html">📂 Software Vault</a>
        <a href="/Games.html">🎮 Games Vault</a>
        <a href="/Radio.html">📡 Media Showreel</a>
        <a href="/Feeds.html">📊 Telemetry Log</a>
        <a href="/Misc.html">☣ Experimental Nodes</a>
    </nav>
    `;
    document.body.prepend(menuContainer);

    // 2. Define the toggle function globally
    window.toggleSidebarMenu = function() {
        const drawer = document.getElementById('sidebarDrawer');
        const hook = document.getElementById('drawerHook');
        
        drawer.classList.toggle('active');
        hook.textContent = drawer.classList.contains('active') ? "CLOSE" : "MENU";
        hook.style.left = drawer.classList.contains('active') ? "340px" : "20px";
    };
});
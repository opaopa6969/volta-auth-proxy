# Responsive Web Design

[日本語版はこちら](responsive.ja.md)

---

## What is it?

Responsive web design is an approach to building web pages that automatically adapt their layout, sizing, and content to fit the screen they are being viewed on -- whether that is a 27-inch desktop monitor, a laptop, a tablet, or a 6-inch phone screen. Instead of building separate "mobile" and "desktop" versions of a website, you build one site that responds to the available screen space.

Think of it like water poured into different-shaped glasses. The water (your content) is always the same, but it takes the shape of whatever glass (screen) it is in. A wide glass shows the water spread out; a narrow glass shows it tall and thin. The water does not change -- only its arrangement.

Responsive design is built on three CSS technologies: **fluid grids** (layouts that use percentages instead of fixed pixels), **flexible images** (images that scale within their containers), and **media queries** (CSS rules that apply only at certain screen widths).

---

## Why does it matter?

Over 60% of web traffic now comes from mobile devices. If your login page, dashboard, or admin panel looks broken on a phone, users will not trust your application -- especially an authentication page where security perception matters.

For volta-auth-proxy specifically:

- **The login page** must work on phones. Employees might need to log in from their personal mobile device.
- **The consent/account picker** is rendered by Google (not volta), but the redirect back to volta's pages must look correct on any screen.
- **The admin dashboard** must be usable on tablets (for on-the-go administration).
- **Error pages** (session expired, access denied) must be readable on any device.

A broken mobile experience on an auth page makes users think: "This service is unprofessional. Can I trust it with my credentials?" First impressions matter, and for many users, the login page IS the first impression.

---

## How does it work?

### Mobile-first approach

volta.css uses a **mobile-first** approach: the default CSS rules are designed for small screens, and larger screens get additional rules via media queries. This is considered best practice because:

1. Mobile styles tend to be simpler (single column, stacked elements)
2. It forces you to prioritize content (what must the user see on a small screen?)
3. Mobile devices are more constrained, so optimizing for them first avoids bloat

```css
/* Mobile first: default styles for small screens */
.login-container {
    width: 100%;
    padding: 1rem;
}

/* Tablet and up */
@media (min-width: 768px) {
    .login-container {
        width: 480px;
        margin: 0 auto;
        padding: 2rem;
    }
}

/* Desktop */
@media (min-width: 1024px) {
    .login-container {
        width: 520px;
        padding: 3rem;
    }
}
```

### The viewport meta tag

For responsive design to work on mobile, you need this in your HTML `<head>`:

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0">
```

Without this, mobile browsers render the page at a virtual "desktop" width (typically 980px) and then shrink it to fit, making everything tiny and unreadable.

### Key CSS techniques

#### Fluid typography

```css
/* Instead of fixed font sizes */
body {
    font-size: 16px; /* base for mobile */
}

@media (min-width: 768px) {
    body {
        font-size: 18px;
    }
}

/* Or using clamp() for smooth scaling */
h1 {
    font-size: clamp(1.5rem, 2.5vw, 2.5rem);
}
```

#### Flexible containers

```css
.card {
    width: 100%;
    max-width: 600px; /* Never wider than 600px */
    margin: 0 auto;   /* Centered on wider screens */
}
```

#### CSS Grid and Flexbox

```css
/* A responsive grid that adapts from 1 to 3 columns */
.dashboard-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 1rem;
}
```

### Common breakpoints

| Breakpoint | Target devices | volta usage |
|-----------|---------------|-------------|
| `< 480px` | Small phones | Login form fills screen, minimal padding |
| `480px - 768px` | Large phones, small tablets | Login form centered, some padding |
| `768px - 1024px` | Tablets, small laptops | Admin sidebar collapses to hamburger menu |
| `> 1024px` | Desktops | Full layout, sidebar visible |

### Testing responsive design

| Method | Description |
|--------|-------------|
| **Browser DevTools** | Chrome/Firefox device mode (F12, toggle device toolbar) |
| **Real devices** | Test on actual phones/tablets (layout engines differ) |
| **Responsive screenshots** | Tools like Percy or Chromatic for visual regression |
| **Lighthouse** | Google's audit tool checks responsive best practices |

---

## How does volta-auth-proxy use it?

volta-auth-proxy's CSS (`volta.css`) is **mobile-first responsive**. Every page volta renders -- login, error, dashboard, admin -- adapts to the user's screen size.

### Pages that must be responsive

| Page | Why mobile matters |
|------|-------------------|
| **Login** | Users may log in from mobile browsers. The Google redirect returns to volta's callback, which renders on whatever device started the flow. |
| **Error pages** | "Session expired" or "Access denied" can appear on any device at any time. |
| **Account settings** | Users may need to change settings or revoke sessions from their phone. |
| **Admin dashboard** | Tenant admins may need to check user activity or respond to alerts while away from their desk. |

### volta.css design principles

1. **Mobile-first**: Default styles target phones. Media queries add complexity for larger screens.
2. **No CSS framework**: volta does not use Bootstrap, Tailwind, or any CSS framework. The CSS is hand-written and minimal, matching volta's philosophy of understanding every line.
3. **Semantic HTML**: jte templates use semantic HTML (`<main>`, `<nav>`, `<section>`), which aids responsive layout and accessibility.
4. **System fonts**: volta uses the user's system font stack, avoiding external font downloads that slow mobile rendering.

### Example: responsive login page

```
  Mobile (< 480px):          Tablet (768px):           Desktop (1024px+):
  ┌──────────────┐          ┌──────────────────┐      ┌─────────────────────────┐
  │ ┌──────────┐ │          │                  │      │         ┌──────────┐    │
  │ │  Logo    │ │          │  ┌──────────┐    │      │         │  Logo    │    │
  │ │          │ │          │  │  Logo    │    │      │         │          │    │
  │ │ [Google] │ │          │  │ [Google] │    │      │         │ [Google] │    │
  │ │  Login   │ │          │  │  Login   │    │      │         │  Login   │    │
  │ │          │ │          │  │          │    │      │         │          │    │
  │ └──────────┘ │          │  └──────────┘    │      │         └──────────┘    │
  │              │          │                  │      │                         │
  └──────────────┘          └──────────────────┘      └─────────────────────────┘
  Full width, minimal       Centered card,             Centered card,
  padding.                  moderate padding.          ample whitespace.
```

---

## Common mistakes and attacks

### Mistake 1: Forgetting the viewport meta tag

Without `<meta name="viewport" content="width=device-width, initial-scale=1.0">`, mobile browsers render at desktop width and zoom out. Everything looks tiny. This is the most common responsive design mistake.

### Mistake 2: Using fixed widths

```css
/* Bad -- breaks on small screens */
.login-form { width: 500px; }

/* Good -- responsive */
.login-form { width: 100%; max-width: 500px; }
```

### Mistake 3: Not testing on real devices

Browser DevTools emulate screen sizes but not touch behavior, viewport quirks, or font rendering. Always test critical pages (especially login) on at least one real iOS and Android device.

### Mistake 4: Hiding important content on mobile

Some developers hide security warnings or error details on mobile to save space. Never hide security-relevant information. If a login fails, the user needs to see the reason on any screen size.

### Mistake 5: Touch target sizes too small

Login buttons, links to "Forgot password," and "Sign in with Google" must be at least 44x44 pixels (Apple's recommendation) or 48x48dp (Google's recommendation) for comfortable tapping. Small touch targets frustrate users and reduce accessibility.

### Attack: Mobile-specific phishing

Attackers create phishing pages that look like legitimate login pages on mobile screens. On desktop, differences might be visible (wrong URL in the address bar, missing HTTPS indicator). On mobile, the address bar is smaller and often hidden. volta mitigates this by encouraging bookmark-based access and prominent domain display on login pages.

---

## Further reading

- [MDN: Responsive web design](https://developer.mozilla.org/en-US/docs/Learn/CSS/CSS_layout/Responsive_Design) -- Comprehensive guide.
- [Google Web Fundamentals: Responsive](https://web.dev/responsive-web-design-basics/) -- Google's best practices.
- [CSS Media Queries](https://developer.mozilla.org/en-US/docs/Web/CSS/Media_Queries/Using_media_queries) -- The technical reference.
- [xss.md](xss.md) -- Security vulnerabilities that affect all page rendering.
- [cors.md](cors.md) -- How cross-origin policies interact with responsive assets.

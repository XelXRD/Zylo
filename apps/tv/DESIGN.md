---
name: Zylo for Android TV
description: One interface. Your entire media world.
colors:
  zylo-red: "#BD1422"
  zylo-red-focus: "#D41A29"
  screening-room: "#101114"
  house-dark: "#191A1E"
  seat-graphite: "#222329"
  raised-graphite: "#292A31"
  focus-graphite: "#3A3B45"
  hairline: "#34353D"
  screen-white: "#F5F5F5"
  aisle-grey: "#A4A5AB"
  dim-grey: "#7F8088"
  success: "#36B37E"
  warning: "#E5A83B"
  error: "#E05252"
  info: "#5799DB"
typography:
  display-large:
    fontFamily: "Inter Display"
    fontSize: "38sp"
    fontWeight: 700
    lineHeight: 1.05
    letterSpacing: "-0.025em"
  display-medium:
    fontFamily: "Inter Display"
    fontSize: "32sp"
    fontWeight: 700
    lineHeight: 1.08
    letterSpacing: "-0.022em"
  display-small:
    fontFamily: "Inter Display"
    fontSize: "26sp"
    fontWeight: 600
    lineHeight: 1.12
    letterSpacing: "-0.018em"
  headline-small:
    fontFamily: "Inter Display"
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.012em"
  title-large:
    fontFamily: "Inter"
    fontSize: "17sp"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "-0.006em"
  title-medium:
    fontFamily: "Inter"
    fontSize: "15sp"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.003em"
  title-small:
    fontFamily: "Inter"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: 1.3
    letterSpacing: "0em"
  body-large:
    fontFamily: "Inter"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "0em"
  body-medium:
    fontFamily: "Inter"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "0em"
  body-small:
    fontFamily: "Inter"
    fontSize: "12.5sp"
    fontWeight: 400
    lineHeight: 1.45
    letterSpacing: "0.004em"
  label-large:
    fontFamily: "Inter"
    fontSize: "13.5sp"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "0.006em"
  label-medium:
    fontFamily: "Inter"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "0.01em"
  label-small:
    fontFamily: "Inter"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "0.015em"
rounded:
  small: "6dp"
  medium: "10dp"
  large: "14dp"
spacing:
  row-gap: "14dp"
  section: "16dp"
  grid-gap: "18dp"
  overscan: "48dp"
components:
  button-primary:
    backgroundColor: "{colors.zylo-red}"
    textColor: "#FFFFFF"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "9dp 18dp"
  button-primary-focused:
    backgroundColor: "{colors.zylo-red-focus}"
    textColor: "#FFFFFF"
  button-secondary:
    backgroundColor: "rgba(255,255,255,0.12)"
    textColor: "{colors.screen-white}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "9dp 18dp"
  button-secondary-focused:
    backgroundColor: "{colors.screen-white}"
    textColor: "{colors.screening-room}"
  poster-card:
    backgroundColor: "{colors.seat-graphite}"
    rounded: "{rounded.medium}"
    width: "124dp"
    height: "186dp"
  preview-card-expanded:
    backgroundColor: "{colors.seat-graphite}"
    rounded: "{rounded.medium}"
    width: "330dp"
    height: "186dp"
  list-surface:
    backgroundColor: "transparent"
    textColor: "{colors.aisle-grey}"
    rounded: "{rounded.small}"
  list-surface-focused:
    backgroundColor: "{colors.focus-graphite}"
    textColor: "{colors.screen-white}"
  nav-item:
    backgroundColor: "transparent"
    textColor: "{colors.aisle-grey}"
    typography: "{typography.label-large}"
    rounded: "{rounded.small}"
    padding: "6dp 12dp"
  nav-item-focused:
    backgroundColor: "{colors.screen-white}"
    textColor: "{colors.screening-room}"
  search-field:
    backgroundColor: "{colors.seat-graphite}"
    textColor: "{colors.screen-white}"
    rounded: "{rounded.medium}"
    height: "52dp"
    padding: "0 16dp"
  status-chip:
    rounded: "{rounded.small}"
    typography: "{typography.label-medium}"
    padding: "4dp 9dp"
---

# Design System: Zylo for Android TV

## Overview

**Creative North Star: "The Lights-Down Cinema"**

When the house lights go down, the screen is the only thing left in the room. Zylo works the same way. The interface is a dark auditorium of graphite surfaces sitting a few steps above black, and the film artwork (posters, backdrops, title logos and muted trailers) is the brightest thing on screen. Chrome falls back so the picture can lead. The top bar is a soft fade rather than a solid bar, the hero is a full-bleed backdrop scrimmed into the page, and cards are mostly artwork with almost no frame.

Zylo red works like the exit sign: small, always in the same places, and never the room itself. It marks the primary Play action, watch progress, the active section and selected items, and nothing else. Focus does the job of a cursor. Because a D-pad remote has no pointer, the focused element must be unmistakable from the sofa. That's done with white: a white outline, a white fill, or a lighter graphite panel with a white edge, plus a small scale-up on artwork. Motion is quick, firm and short, because a remote user makes the same focus move hundreds of times a session.

The system is calm and dense rather than spectacular. Discovery screens can bring their own mood (Deep Cuts' dossier monospace, Blind Date's wine-dark wash), but they do it inside the same dark room.

**Key Characteristics:**
- Near-black graphite surfaces in tonal steps, no light theme.
- Artwork is the colour. UI colour is limited to one red and four status hues.
- White is the focus language: outline, fill, or a lighter panel, never a coloured ring.
- Inter for UI, with Inter Display for titles, tightly tracked at size.
- Motion under 300 ms, with strong custom easing and full support for Reduce motion.
- 48dp overscan-safe side margin on every screen.

## Colors

A graphite-on-black auditorium with a single cinema-red signal and four quiet status hues.

### Primary
- **Zylo Red** (`zylo-red`): the primary Play / Resume / Continue button, progress bars on part-watched cards and in the player, the 16×2dp tick under the active top-bar section, and 18% red tints on selected list rows and chips. It's also the label colour for Deep Cuts' case-file headings.
- **Zylo Red, lit** (`zylo-red-focus`): the focused state of a primary button, a step brighter so focus reads even on red.

### Neutral
- **Screening Room** (`screening-room`): the app background and the base of every scrim and gradient. Hero and top-bar fades resolve into it.
- **House Dark** (`house-dark`): large panels set into the page, such as dialogs, the source picker and info sheets (with `large` corners).
- **Seat Graphite** (`seat-graphite`): cards, artwork placeholders, skeletons (at 70%), the search field and the Show More tile.
- **Raised Graphite** (`raised-graphite`): small raised elements such as service initial tiles.
- **Focus Graphite** (`focus-graphite`): the fill of a focused list row or tab, always paired with a white outline.
- **Hairline** (`hairline`): dividers, unfocused field strokes and the vertical separators in filter chip rows.
- **Screen White** (`screen-white`): primary text, focus outlines and focused nav and secondary-button fills.
- **Aisle Grey** (`aisle-grey`): secondary text, metadata lines and unselected nav labels. It's the most-used colour in the codebase.
- **Dim Grey** (`dim-grey`): hints, placeholder titles under loading artwork and tertiary notes.

### Status
- **Success / Warning / Error / Info** (`success`, `warning`, `error`, `info`): status chips (a 14% tint behind a 7dp dot and coloured label) and connection states in Settings. Use these for state only, never for decoration.

### Named Rules
**The Exit Sign Rule.** Zylo red is rare and fixed in place: primary Play-type buttons, progress, the active-section tick and selection tints. It is never a background field, a card fill, a headline colour or a decorative accent. If two red things compete on one screen, one of them is wrong.

**The Artwork Is the Colour Rule.** The UI adds no hues of its own beyond red and status. Colour on screen should come from posters, backdrops and trailers.

**The Mood Wash Exception.** A discovery screen may set a near-black tinted atmosphere: Deep Cuts' vertical wash (`#07080A → #130A0C → #07080A`) or Blind Date's radial wine glow (`#3A0A12 → screening-room`). These washes stay below 25% lightness and never use Zylo Red itself.

**The Moodboard Palette.** Moodboard is the one place colour names a feeling: each mood has its own hue (Rainy Sunday `#3D5A80`, Neon Noir, Haunted Hours and the rest in `Explore.kt`), shown at 55% on its tile and as a 45% wash fading into Screening Room on its page. It doesn't spread to other screens.

**Profile Colours.** Avatars use their own small palette (rose `#9A4155`, teal, amber, blue, green, brown). Zylo Red is not one of them; older red profiles render in rose.

## Typography

**Display Font:** Inter Display (SemiBold, Bold)
**Body Font:** Inter (Regular, Medium, SemiBold, Bold)

**Character:** One family in two cuts. Inter Display is drawn for large sizes, so titles stay crisp and tight instead of looking like enlarged body text. Inter carries everything at reading and label sizes. The guideline sizes are for a 1080p canvas, and TV dp is half of that, so 64px is about 32sp.

### Hierarchy
- **Display Large** (Inter Display Bold, 38sp, 1.05): the featured hero title on Home, when there's no title logo.
- **Display Medium** (Inter Display Bold, 32sp, 1.08): the title on a detail page.
- **Display Small** (Inter Display SemiBold, 26sp, 1.12): page headings.
- **Headline Small** (Inter Display SemiBold, 20sp, 1.2): panel and dialog headings.
- **Title Large** (Inter SemiBold, 17sp, 1.25): row and section headings ("Continue Watching").
- **Title Medium / Small** (Inter SemiBold 15sp / Medium 14sp): card titles and fallback title text in preview cards.
- **Body Large / Medium** (Inter Regular 15 / 14sp, 1.5): overviews and explanatory copy. Keep body measure to about 60% of screen width (the Welcome screen uses `fillMaxWidth(0.6f)`).
- **Body Small** (Inter Regular 12.5sp, 1.45): metadata lines, joined with a spaced middle dot (`  ·  `) and kept to one line with an ellipsis.
- **Label Large / Medium / Small** (Inter Medium 13.5 / 12 / 11sp): buttons, nav items, chips and badges. Labels are sentence or title case, not upper case.

### Named Rules
**The Tracking Follows Size Rule.** Large type tightens (down to −2.5% at 38sp), and small labels open slightly (up to +1.5% at 11sp). Leading runs the other way: tight on titles, generous (1.5) on body. Pick a role from the type scale; never set a one-off size.

**The Title Logo First Rule.** When fanart.tv or TMDB has a title logo, show it in place of the typeset title on heroes and preview cards. Typeset Display is the fallback.

## Layout

The canvas is a 16:9 TV at 1080p, laid out in dp. Every screen keeps a **48dp overscan-safe side margin** (`overscan`). Discovery reveal screens use double that (96dp) for a more ceremonial composition.

- **Rows** are horizontal shelves with 14dp gaps between cards (`row-gap`). A row bleeds past the page padding so that focus scaling isn't clipped, and its content padding puts it back on the margin. The focused card sits at the left edge of the row. The active row is centred vertically. Up and Down keep the horizontal position, and every row ends with a Show More tile.
- **Grids** (Movies, Series, Anime) use 18dp horizontal and 20dp vertical gaps with 2:3 posters.
- **Hero** on Home is a full-bleed backdrop with 440dp of info area, anchored bottom-left. The title or logo block is 48% of the width, with buttons 18dp below and 12dp apart, and the page dots below that.
- **Top bar** floats over content, with 14dp above and 22dp below the items, inside a fade.
- **Scrolling** brings the focused item into view with only a 24dp lead and trail. It never jumps to a fixed focal point.
- **Skeletons** match the shape of what's loading (124×186dp posters and 160×16dp headings) and are still, with no shimmer.

## Elevation & Depth

Depth comes from tonal stepping, not drop shadows. Surfaces rise from `screening-room` to `house-dark`, `seat-graphite`, `raised-graphite` and then `focus-graphite`. Over artwork, depth comes from scrims: Screening Room gradients that let the picture show through at the top and resolve to solid at the bottom or left.

There is one shadow, and it only appears with focus. A focused card lifts with a deep, soft black glow (90% black, 18dp radius) together with its scale-up. Nothing has a shadow at rest.

### Shadow Vocabulary
- **Focus lift** (`Glow(black 90%, 18dp)`): focused cards only.

### Named Rules
**The Flat at Rest Rule.** Surfaces are flat until focused. Lift, scale and glow are responses to focus, never decoration.

**The Scrim, Not the Box Rule.** Text over artwork sits on a gradient into Screening Room or black (hero: horizontal 92% → 55% → clear at 75%, plus a vertical fade to solid; preview cards: clear at 30% → 92% black). Never put text in a solid box over artwork.

## Shapes

Gently rounded, never pill-shaped and never sharp. There are three radii. **Small (6dp)** is for buttons, chips, list rows, nav items, tiles and progress indicators. **Medium (10dp)** is for cards, artwork, skeletons and the search field. **Large (14dp)** is for panels and dialogs. Avatars and the watched tick are the only circles. Artwork is always clipped to its card radius. Borders appear only as focus outlines (2dp) or as hairline field strokes.

## Components

### Buttons
Firm, compact and remote-legible.
- **Shape:** gently rounded (small, 6dp). Padding is 9dp vertical and 18dp horizontal, with an optional 18dp leading glyph and 8dp gap.
- **Primary:** a Zylo Red fill with white label-large text. When focused it turns Zylo Red, lit, with a 2dp white outline.
- **Secondary:** a translucent white fill (12%) with Screen White text. When focused it fills Screen White with Screening Room text.
- **Focus / Press:** scales to 1.05 on focus (1.0 with Reduce motion) and squeezes to 0.96 when OK is pressed. One primary button per cluster.

### Chips
- **Status chip:** a 14% tint of the status colour, a 7dp dot, a 6dp gap and a label-medium label in the status colour, with small corners.
- **Filter chips** (the Movies, Series and Anime filter rows) use the list-surface treatment, separated into groups by 1×26dp hairline dividers.

### Cards / Containers
- **Poster card:** 2:3, 124×186dp in rows, Seat Graphite behind artwork and medium corners. The title is drawn under the image in Dim Grey, so a missing or slow image never leaves a blank card.
- **Focus:** scales to 1.08, gets a 2dp white edge (92%) inset on the artwork, and the focus-lift glow.
- **Marks:** a 3dp Zylo Red progress bar on a 25% white track along the bottom edge when part-watched. A 22dp Screening Room circle with a 14dp tick sits bottom-right when watched.
- **Preview card:** after resting on a card for 800ms, it widens from 124 to 330dp over 280ms with ease-in-out, crossfades to the backdrop with a title logo, a metadata line and a two-line overview, and then plays a muted trailer once fully grown. It shrinks instantly, because an animated shrink drags the row sideways.
- **Panels:** House Dark with large corners and 24dp padding.

### Dialogs and text entry
- **One text dialog for everything** (`ask()` in `Ask.kt`): keys, PINs, links, names. House Dark panel, large corners, 700dp wide, 28dp padding. Title in Headline Small, an optional hint line (what's expected and where to find it) in Aisle Grey.
- **Field:** the search-field treatment, with Inter and a Zylo Red selection highlight. Secrets and PINs are masked; a **Show** button reveals them. Problems appear under the field in Error with a red stroke, never in a pop-up.
- **Buttons:** Save (primary) first, then Cancel; Show and **Send from Phone** (QR code to a one-page form on the home Wi-Fi) sit on the right. Down from the field goes to Save; the keyboard's tick saves.
- **Choice lists:** settings with more than a handful of values (languages) open a list dialog with the current value focused and ticked.

### Notices
- Short confirmations sit at the foot of the screen in a Raised Graphite panel with an Info dot, for five seconds. Errors that need action belong next to their cause instead.

### Inputs / Fields
- **Search field:** Seat Graphite, medium corners, 52dp tall, 16dp side padding, 17sp text. The 2dp stroke is Hairline at rest and Screen White on focus. Hint text is Dim Grey.

### Navigation
- **Top bar:** the logo (28dp mark and wordmark, not focusable), then section labels in label-large, then the background-activity chip, Search, Settings and the profile avatar on the right. It sits on a fade from Screening Room at 80% to 35% to clear, not on a solid bar.
- **States:** unselected items are Aisle Grey. The selected item is Screen White with a 16×2dp Zylo Red tick under it. A focused item fills Screen White with Screening Room text and doesn't scale.
- **List rows and tabs:** transparent with Aisle Grey text. When focused they get a Focus Graphite fill, a 2dp Screen White outline and Screen White text, with no scale. Selected rows get an 18% Zylo Red tint.

### Service cards
- Tile, name, status chip and actions on one line; the description, or what went wrong in plain words, runs full width underneath (up to 640dp, three lines).
- **Connect** is primary (Zylo Red) only for TMDB, the one required key. Optional services connect with a secondary button.
- **Disconnect** and **Remove** are two-press (`ConfirmButton`): the first press turns the label into "Press again" for three seconds.

### Discover (signature)
- A spotlight panel (236dp, large corners) features a random game each time Discover opens, over artwork kept well back behind a strong left scrim. It enters with a slow push-in on the artwork while the words rise (1.4s; still with Reduce motion). Rabbit Hole in the spotlight starts from your last watched title. Its **Start** is the screen's one red button. Scrolled under the top bar, its words and Start fade out and the artwork stays on, dimmed.
- Below, every game in three named rows (Decide together, Go deeper, Keep track) as 260x146dp artwork tiles (`DiscoverTiles.kt`): real artwork from Discover's rows, reshuffled each visit, name and line over a bottom scrim. On focus each tile acts out its game once (0.65s): Movie Night Match's posters fan out, Help Me Choose's pair squares up around "or", Blind Date sharpens out of a blurred wine-dark wash, Double Feature's pair slides together, Rabbit Hole zooms in, Deep Cuts' redaction bar slides away, Franchise Journeys' pile lays out in order, Moodboard changes mood colour, Time Machine dials back to its year in that decade's tint, Calendar flicks through four weeks and settles on today, Watch Challenges shows your mosaic, Wrapped lights your recent posters. Reduce motion shows the end state at once.
- Genre rows follow; Home's trending rows aren't repeated.

### Hero (signature)
The Home hero is a full-bleed backdrop behind horizontal and vertical Screening Room scrims, with a title logo or Display Large title, a metadata line, Play and secondary buttons, and page dots (the active dot is Screen White and the rest are 30%). The trailer starts after 3s at rest. The set of hero titles changes every 4 hours.

### Trailers
- Trailers are YouTube embeds, but YouTube's interface never shows: the player is drawn 24% larger than its box so corner badges fall outside, starts 4s in (past rating cards), stays under the artwork, silent, until 3.5s of playback (YouTube's title bar has gone), then the sound fades up with the picture, drops captions, and hands back to the artwork 3s before the end. Countdowns, reactions, recaps, featurettes and similar aren't used as trailers.

### Player
- Controls take Zylo's focus (Focus Graphite with a 2dp white edge, 10dp corners), never Media3's default highlight.
- **Errors** sit in a House Dark panel over a 94% Screening Room dim: a plain headline ("This TV can't play this file", "The link didn't work", "This source sent a holding clip"), one sentence on why and what next, then **Try Next Source** (primary, focused) and **Choose Source**. The player's own code sits underneath in Dim Grey. One Back returns to the list.
- A source only counts once frames actually play: nothing is saved, scrobbled or remembered for a source that failed or sent a holding clip.

### Choose Source
- Files whose names plainly belong to another title are left out, with a muted line saying how many. Sources that failed in the player are marked **Unavailable** for the rest of the visit.

### Nav shade
- Once a page scrolls, a Screening Room band (solid to ~92dp, fading out by 124dp) sits behind the top bar, and the page's first item (hero text or page title) fades as it scrolls up, so nothing runs into the navigation.

### Motion
- **Ease out** `cubic-bezier(0.23, 1, 0.32, 1)` for things arriving or answering a press. **Ease in-out** `cubic-bezier(0.77, 0, 0.175, 1)` for things changing shape.
- Durations: 180ms (fast) and 240ms (normal). UI motion stays under 300ms.
- Every duration goes through Reduce motion, which brings it to 0, and focus scaling drops to 1.0 when it's on.

### Opening (House Lights Down)
On a cold start only (not a profile switch or a return from the background), `Intro.kt` plays over the app while it loads: the room dims from graphite to near black, a soft projector beam reaches in from the upper left, the Z lights up in it with a red glow, and the wordmark follows (about 2s, then a 0.45s fade into the app). Any button skips it and is swallowed. Its sound (`res/raw/zylo_intro.wav`, synthesised by `tools/intro_sound.py`, replaceable with a designed sting of the same name) follows the same beats and can be turned off in Settings > Playback > Opening. Reduce motion skips the whole opening.

### Lights Up (rewards)
The game-like pages (Watch Challenges, Zylo Wrapped, This or That) share one reward language, in `Reward.kt`: artwork you haven't earned sits desaturated at 50% brightness, and earning it brings the colour up. In Challenges, posters light up once watched, a "Seen" stamp lands on a spring, and a finished challenge gets a one-time ceremony mosaic. In Wrapped, numbers count up while the year's artwork lights. In This or That, the title you passed on drains to grey and falls away. Only things that changed since your last visit light up in front of you (`Lights.fresh`). These reward moments are the one exception to the 300ms limit (up to about 1.6s). Focus movement never slows, and Reduce motion skips all of it.

## Do's and Don'ts

### Do:
- **Do** keep every screen on Screening Room (`#101114`) and step up through the graphite surfaces for depth.
- **Do** make focus unmistakable with white: a 2dp outline, a white fill, or Focus Graphite plus an outline. Pair cards with a 1.08 scale and the focus-lift glow.
- **Do** keep a 48dp side margin and let rows bleed so focus scaling is never clipped.
- **Do** pick text sizes from the type scale roles, with Inter Display at 20sp and above.
- **Do** put text over artwork on a Screening Room or black gradient scrim.
- **Do** route every animation duration through `ms()` so Reduce motion works.
- **Do** show the title under artwork as a fallback, and use still skeletons in the shape of the content.
- **Do** use `ask()` for any typed answer and `ConfirmButton` for anything that throws something away.
- **Do** say what went wrong and what to do next (`plainError()`); never show a raw exception message.

### Don't:
- **Don't** use Zylo Red as a background, card fill, headline colour or decoration (the Exit Sign Rule).
- **Don't** fill a focused surface with white when the text inside is white. Use Focus Graphite and an outline instead.
- **Don't** add drop shadows to resting surfaces.
- **Don't** introduce new UI hues. Colour comes from artwork, and status colours mean state.
- **Don't** use shimmer on loading states.
- **Don't** animate a card shrinking inside a scrolling row, or let any UI motion run past 300ms.
- **Don't** use emoji or Unicode symbols as icons. Use the Material icon set (the thumbs-down is the thumbs-up turned over), or no icon.
- **Don't** use Android's stock dialogs or toasts; they bring Roboto, pill buttons and an orange focus ring into the room.
- **Don't** let YouTube's own interface (title, channel badge, share, suggestions, captions) appear anywhere in Zylo.
- **Don't** redraw or recolour the Zylo mark and wordmark. Use Alex's artwork (`zylo_mark.xml`, `zylo_wordmark.xml`), with the wordmark tinted only to the text colour.

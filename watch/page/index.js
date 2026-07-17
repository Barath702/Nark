import { createWidget, deleteWidget, widget, prop, align, text_style } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import {
  setWakeUpRelaunch,
  setPageBrightTime,
  resetPageBrightTime,
  setAutoBrightness,
  getAutoBrightness,
  setBrightness,
  getBrightness
} from '@zos/display'

const SPRITE_CELL_SIZE = 150

const ICON_MAP = {
  'u-turn-right': 0,          'u-turn-left': 1,           'roundabout-right': 2,      'roundabout-left': 3,
  'destination': 4,           'arrive': 4,                'straight': 5,              'continue': 5,
  'slight-left': 6,           'slight-right': 7,
  'fork-left': 8,             'fork-right': 9,            'turn-left': 10,            'turn-right': 11,
  'roundabout-exit-left': 12, 'roundabout-exit-right': 13,'merge': 14,                'roundabout-straight': 15
}

// ─── Colours ────────────────────────────────────────────────────
const C_WHITE   = 0xFFFFFF
const C_BLUE    = 0x4A90E2
const C_LABEL   = 0x555566
const C_DIVIDER = 0x1C1C22

// ─── Compass-heading pattern (Google Maps "Head [direction]") ───
const COMPASS_RE = /^head\s+(north(?:east|west)?|south(?:east|west)?|east|west)$/i

// ─── Build the navigation phrase shown on screen 2 ──────────────
function buildPhrase(street, rawPhrase) {
  // Street is a compass direction, not a road name
  const m = street && street.match(COMPASS_RE)
  if (m) {
    const dir = m[1]
    return 'Continue to ' + dir.charAt(0).toUpperCase() + dir.slice(1).toLowerCase()
  }
  // Fix if the full phrase also says "Continue on Head [direction]"
  if (rawPhrase) {
    const fixed = rawPhrase.replace(
      /Continue\s+on\s+Head\s+(northeast|northwest|southeast|southwest|north|south|east|west)/gi,
      (_, d) => 'Continue to ' + d.charAt(0).toUpperCase() + d.slice(1).toLowerCase()
    )
    if (fixed.trim().length > 0) return fixed.trim()
  }
  return street || '--'
}

// ─── Resolve arrival time string → { h, m, diff } using current clock ─
// Handles both pure 24h strings ("21:52") and ambiguous 12h strings
// ("11:35" with no AM/PM) by preferring whichever interpretation gives
// a smaller, positive, plausible time-remaining value.
function resolveArrivalTime(str) {
  if (!str || str === '--') return null

  // Parse the raw string
  let h, m
  const m24 = str.match(/^(\d{1,2}):(\d{2})$/)
  const m12 = str.match(/^(\d{1,2}):(\d{2})\s*(am|pm)/i)

  if (m24) {
    h = parseInt(m24[1], 10)
    m = parseInt(m24[2], 10)
  } else if (m12) {
    h = parseInt(m12[1], 10)
    m = parseInt(m12[2], 10)
    if (m12[3].toLowerCase() === 'pm' && h !== 12) h += 12
    if (m12[3].toLowerCase() === 'am' && h === 12) h = 0
    // Already resolved AM/PM — return immediately
    const now2 = new Date()
    let diff2  = h * 60 + m - (now2.getHours() * 60 + now2.getMinutes())
    if (diff2 < 0) diff2 += 1440
    return { h, m, diff: diff2 }
  } else {
    return null  // unrecognised format
  }

  // Ambiguous 24h-looking string (no explicit AM/PM) — use context
  const now    = new Date()
  const nowMin = now.getHours() * 60 + now.getMinutes()

  function fwdDiff(ah, am) {
    let d = ah * 60 + am - nowMin
    if (d < 0) d += 1440
    return d
  }

  let diff = fwdDiff(h, m)

  // If the hour is <= 12, the server might have sent a 12h value
  // without AM/PM (e.g. "11:35" meaning 23:35, or "12:05" meaning 00:05).
  // Find its 12h twin and pick whichever interpretation is sooner.
  if (h <= 12) {
    const twinH    = h === 12 ? 0 : h + 12
    const twinDiff = fwdDiff(twinH, m)
    if (twinDiff <= 720 && twinDiff < diff) {
      return { h: twinH, m, diff: twinDiff }
    }
  }

  return { h, m, diff }
}

// (formatArrival and computeTimeLeft are derived inline inside updateUi
//  from a single resolveArrivalTime() call to avoid duplicate work.)

// ─── Page ───────────────────────────────────────────────────────
Page({
  state: {
    // Screen 1 (always present)
    distanceWidget:    null,
    streetWidget:      null,
    statusWidget:      null,
    subStatusWidget:   null,
    iconWidget:        null,
    wDot1:             null,   // right-side dot — active on screen 1
    wDot2:             null,   // right-side dot — inactive on screen 1

    // Screen 2 (created / deleted dynamically)
    wPhrase:           null,
    wDiv1:             null,
    wTimeLabel:        null,
    wTimeVal:          null,
    wDiv2:             null,
    wEtaLabel:         null,
    wEtaVal:           null,
    wDot1S2:           null,   // right-side dot — inactive on screen 2
    wDot2S2:           null,   // right-side dot — active on screen 2

    navPanelActive:    false,  // tracks whether screen 2 widgets exist

    // Misc
    timerId:            null,
    dimTimer:           null,
    screenWidth:        432,
    screenHeight:       480,
    iconShowY:          100,
    originalBrightness: 80,
    wasAutoBrightness:  false,

    // ── Render-diff cache ─────────────────────────────────────
    // Stores the last value pushed to each widget so we skip setProperty
    // calls when nothing has changed (saves CPU + display pipeline work).
    prev: {
      hasValid:    null,    // last known hasValidData boolean
      distance:    null,
      street:      null,
      iconKey:     null,    // stringified icon value
      status:      null,
      phraseKey:   null,    // street + '|' + phrase fingerprint
      arrivalTime: null,
      lastMinute:  -1       // minute at last time-left calculation
    }
  },

  onInit() {
    try {
      const info = getDeviceInfo()
      if (info && info.width)  this.state.screenWidth  = info.width
      if (info && info.height) this.state.screenHeight = info.height
    } catch (e) {}

    setWakeUpRelaunch({ relaunch: true })
    setPageBrightTime({ brightTime: 60000 })

    try {
      this.state.wasAutoBrightness  = getAutoBrightness()
      this.state.originalBrightness = getBrightness()
      setAutoBrightness({ autoBright: false })
    } catch (e) {}

    this.startDimTimer()
    this.state.timerId = setInterval(() => { this.updateUi() }, 500)
  },

  startDimTimer() {
    if (this.state.dimTimer) { clearTimeout(this.state.dimTimer); this.state.dimTimer = null }
    this.state.dimTimer = setTimeout(() => {
      try { setBrightness({ brightness: 1 }); this.state.isDimmed = true } catch (e) {}
    }, 10000)
  },

  // ── Build: only screen 1 content ──────────────────────────────
  build() {
    const W = this.state.screenWidth
    const H = this.state.screenHeight

    const iconY     = Math.round(H * 0.18)
    const distanceY = iconY + SPRITE_CELL_SIZE + 6
    const streetY   = distanceY + 64

    this.state.iconShowY = iconY

    // Connecting / connected status
    this.state.statusWidget = createWidget(widget.TEXT, {
      x: 0, y: Math.floor(H / 2) - 45, w: W, h: 80,
      color: C_LABEL, text_size: 56,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text: 'Connecting...'
    })
    this.state.subStatusWidget = createWidget(widget.TEXT, {
      x: 0, y: Math.floor(H / 2) + 42, w: W, h: 50,
      color: C_WHITE, text_size: 30,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text: '', visible: false
    })

    // Nav arrow (hidden off-screen until data arrives)
    this.state.iconWidget = createWidget(widget.IMG, {
      x: Math.floor((W - SPRITE_CELL_SIZE) / 2) + 3,
      y: -500,
      w: SPRITE_CELL_SIZE, h: SPRITE_CELL_SIZE,
      src: 'icon_5.png'
    })

    // Distance to next turn — blue, large
    this.state.distanceWidget = createWidget(widget.TEXT, {
      x: 10, y: distanceY, w: W - 20, h: 68,
      color: C_BLUE, text_size: 56,
      align_h: align.CENTER_H, text: '--', visible: false
    })

    // Street name — white
    this.state.streetWidget = createWidget(widget.TEXT, {
      x: 20, y: streetY, w: W - 40, h: 112,
      color: C_WHITE, text_size: 40,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text_style: text_style.WRAP, text: '', visible: false
    })

    // Right-side page indicator dots (shown only when navigating)
    // Dot 1 — active / white (you are on screen 1)
    this.state.wDot1 = createWidget(widget.FILL_RECT, {
      x: W - 14, y: Math.floor(H / 2) - 10,
      w: 8, h: 8, color: C_WHITE, radius: 4, visible: false
    })
    // Dot 2 — inactive / grey (screen 2 is below)
    this.state.wDot2 = createWidget(widget.FILL_RECT, {
      x: W - 14, y: Math.floor(H / 2) + 4,
      w: 8, h: 8, color: 0x444444, radius: 4, visible: false
    })
  },

  // ── Dynamically create screen 2 when navigation starts ─────────
  createDetailsPanel() {
    const W = this.state.screenWidth
    const H = this.state.screenHeight
    const S = H   // Screen 2 y-offset

    // Right-side page dots for screen 2
    // Dot 1 — inactive (screen 1 is above)
    this.state.wDot1S2 = createWidget(widget.FILL_RECT, {
      x: W - 14, y: S + Math.floor(H / 2) - 10,
      w: 8, h: 8, color: 0x444444, radius: 4
    })
    // Dot 2 — active / white (you are on screen 2)
    this.state.wDot2S2 = createWidget(widget.FILL_RECT, {
      x: W - 14, y: S + Math.floor(H / 2) + 4,
      w: 8, h: 8, color: C_WHITE, radius: 4
    })

    // Full navigation phrase — large white text, generous height
    this.state.wPhrase = createWidget(widget.TEXT, {
      x: 28, y: S + 46, w: W - 56, h: 188,
      color: C_WHITE, text_size: 44,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text_style: text_style.WRAP, text: '--'
    })

    // ── Divider 1 ────────────────────────────────────
    this.state.wDiv1 = createWidget(widget.FILL_RECT, {
      x: 28, y: S + 242, w: W - 56, h: 1, color: C_DIVIDER
    })

    // ── TIME LEFT ────────────────────────────────────
    this.state.wTimeLabel = createWidget(widget.TEXT, {
      x: 0, y: S + 250, w: W, h: 24,
      color: C_LABEL, text_size: 20,
      align_h: align.CENTER_H, text: 'TIME LEFT'
    })
    // Large number, same weight as Zepp workout metrics
    this.state.wTimeVal = createWidget(widget.TEXT, {
      x: 0, y: S + 276, w: W, h: 74,
      color: C_BLUE, text_size: 58,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text: '--'
    })

    // ── Divider 2 ────────────────────────────────────
    this.state.wDiv2 = createWidget(widget.FILL_RECT, {
      x: 28, y: S + 356, w: W - 56, h: 1, color: C_DIVIDER
    })

    // ── ARRIVAL ──────────────────────────────────────
    this.state.wEtaLabel = createWidget(widget.TEXT, {
      x: 0, y: S + 364, w: W, h: 24,
      color: C_LABEL, text_size: 20,
      align_h: align.CENTER_H, text: 'ARRIVAL'
    })
    // Large, prominent railway-time display
    this.state.wEtaVal = createWidget(widget.TEXT, {
      x: 0, y: S + 390, w: W, h: 82,
      color: C_BLUE, text_size: 64,
      align_h: align.CENTER_H, align_v: align.CENTER_V,
      text: '--:--'
    })
  },

  // ── Delete all screen 2 widgets when navigation stops ──────────
  destroyDetailsPanel() {
    const names = [
      'wDot1S2', 'wDot2S2',
      'wPhrase',
      'wDiv1', 'wTimeLabel', 'wTimeVal',
      'wDiv2', 'wEtaLabel',  'wEtaVal'
    ]
    names.forEach(n => {
      if (this.state[n]) {
        try { deleteWidget(this.state[n]) } catch (e) {}
        this.state[n] = null
      }
    })
  },

  // ── Update both screens every 500 ms ───────────────────────────
  // Uses a diff cache (this.state.prev) so setProperty is only called
  // when a value actually changes — avoids redundant display pipeline
  // work on the watch CPU during steady-state navigation.
  updateUi() {
    try {
      const app   = getApp()
      const cache = (app && app._options && app._options.globalData)
        ? app._options.globalData.lastNavData : null
      if (!cache) return

      const hasValidData = (
        cache.distance !== '--' &&
        cache.street   !== 'Not navigating' &&
        cache.street   !== ''
      )

      const p          = this.state.prev
      const navChanged = (hasValidData !== p.hasValid)

      // ── Visibility toggling — only on nav-state change ────────
      if (navChanged) {
        // Clear text caches so every widget gets a fresh value this tick
        p.distance = null; p.street = null; p.iconKey  = null
        p.status   = null; p.phraseKey = null
        p.arrivalTime = null; p.lastMinute = -1

        this.state.distanceWidget.setProperty(prop.VISIBLE, hasValidData)
        this.state.streetWidget.setProperty(prop.VISIBLE, hasValidData)
        this.state.statusWidget.setProperty(prop.VISIBLE, !hasValidData)
        this.state.subStatusWidget.setProperty(prop.VISIBLE, !hasValidData)
        this.state.wDot1.setProperty(prop.VISIBLE, hasValidData)
        this.state.wDot2.setProperty(prop.VISIBLE, hasValidData)
        if (!hasValidData) this.state.iconWidget.setProperty(prop.Y, -500)
        else               this.state.iconWidget.setProperty(prop.Y, this.state.iconShowY)
      }

      if (hasValidData) {
        // ── Screen 1: text — only when data changed ────────────
        if (cache.distance !== p.distance) {
          this.state.distanceWidget.setProperty(prop.TEXT, String(cache.distance))
          p.distance = cache.distance
        }
        if (cache.street !== p.street) {
          this.state.streetWidget.setProperty(prop.TEXT, String(cache.street))
          p.street = cache.street
        }

        // Icon SRC — only when icon key changes
        const iconKey = String(cache.icon)
        if (iconKey !== p.iconKey) {
          let idx = 5
          if (cache.icon !== undefined && cache.icon !== null) {
            if (typeof cache.icon === 'string') {
              const norm = cache.icon.toLowerCase().replace(/_/g, '-')
              idx = ICON_MAP[norm] !== undefined ? ICON_MAP[norm] : 5
            } else if (typeof cache.icon === 'number') {
              idx = cache.icon
            }
          }
          this.state.iconWidget.setProperty(prop.SRC, `icon_${idx}.png`)
          // Ensure icon is on-screen (may already be if only SRC changed)
          if (!navChanged) this.state.iconWidget.setProperty(prop.Y, this.state.iconShowY)
          p.iconKey = iconKey
        }

      } else {
        // ── Screen 1: status text — only when status changes ───
        const status = cache.status || ''
        if (status !== p.status) {
          if (status === 'Connected') {
            this.state.statusWidget.setProperty(prop.COLOR, C_BLUE)
            this.state.statusWidget.setProperty(prop.TEXT, 'Connected')
            this.state.subStatusWidget.setProperty(prop.TEXT, 'Start navigating')
          } else {
            this.state.statusWidget.setProperty(prop.COLOR, C_LABEL)
            this.state.statusWidget.setProperty(prop.TEXT, 'Connecting...')
            this.state.subStatusWidget.setProperty(prop.TEXT, '')
          }
          p.status = status
        }
      }

      // ── Screen 2 lifecycle ────────────────────────────────────
      if (hasValidData && !this.state.navPanelActive) {
        this.state.navPanelActive = true
        this.createDetailsPanel()
      } else if (!hasValidData && this.state.navPanelActive) {
        this.state.navPanelActive = false
        this.destroyDetailsPanel()
      }

      // ── Screen 2: data — only when relevant fields change ────
      if (hasValidData && this.state.wPhrase) {
        // Phrase: rebuild regex only when street or raw phrase changes
        const phraseKey = (cache.street || '') + '|' + (cache.phrase || '')
        if (phraseKey !== p.phraseKey) {
          this.state.wPhrase.setProperty(prop.TEXT,
            buildPhrase(cache.street || '', cache.phrase || ''))
          p.phraseKey = phraseKey
        }

        // Time & arrival: call resolveArrivalTime once per minute tick
        // or when the server sends a new arrivalTime string.
        const nowMin = new Date().getMinutes()
        if (cache.arrivalTime !== p.arrivalTime || nowMin !== p.lastMinute) {
          const t       = resolveArrivalTime(cache.arrivalTime)
          const arrival = t
            ? t.h.toString().padStart(2, '0') + ':' + t.m.toString().padStart(2, '0')
            : '--:--'
          const diff    = t ? t.diff : 9999
          const timeLeft = diff <= 720
            ? (diff >= 60
                ? Math.floor(diff / 60) + ':' + (diff % 60).toString().padStart(2, '0')
                : (diff % 60) + ' min')
            : '--'

          this.state.wTimeVal.setProperty(prop.TEXT, timeLeft)
          this.state.wEtaVal.setProperty(prop.TEXT, arrival)
          p.arrivalTime = cache.arrivalTime
          p.lastMinute  = nowMin
        }
      }

      // Commit nav-active flag last so navChanged is accurate next tick
      p.hasValid = hasValidData
    } catch (err) {
      console.log('[Nark] UI update error:', err)
    }
  },

  onShow() {
    try { setBrightness({ brightness: this.state.originalBrightness }); this.state.isDimmed = false } catch (e) {}
    setPageBrightTime({ brightTime: 60000 })
    this.startDimTimer()
  },

  onHide() {
    // Restore original brightness when screen turns off so it’s correct on wake
    // (onShow will also restore it, but this prevents a brightness glitch)
    try { setBrightness({ brightness: this.state.originalBrightness }) } catch (e) {}
  },

  onDestroy() {
    if (this.state.timerId) clearInterval(this.state.timerId)
    if (this.state.dimTimer) clearTimeout(this.state.dimTimer)
    this.destroyDetailsPanel()
    try {
      resetPageBrightTime()
      setAutoBrightness({ autoBright: this.state.wasAutoBrightness })
      setBrightness({ brightness: this.state.originalBrightness })
    } catch (e) {}
  }
})

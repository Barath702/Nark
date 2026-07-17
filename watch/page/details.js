import { createWidget, widget, prop, align, text_style } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import { back } from '@zos/router'
import { onGesture, offGesture, GESTURE_UP } from '@zos/interaction'

// ─── Colour palette ──────────────────────────────────────────────
const C_BG          = 0x0A0A0F   // near-black background
const C_PHRASE      = 0xFFFFFF   // white  – full nav phrase
const C_DIST_VAL    = 0xFFFFFF   // white  – distance remaining value
const C_TIME_VAL    = 0x4A90E2   // blue   – time remaining value
const C_ETA_VAL     = 0xF5C518   // amber  – arrival time value
const C_LABEL       = 0x555566   // muted grey – section labels
const C_DIVIDER     = 0x1A1A2E   // dark divider

Page({
  state: {
    timerId:       null,
    // widgets
    wPhrase:       null,
    wTimeLabel:    null, wTimeVal:   null,
    wDistLabel:    null, wDistVal:   null,
    wEtaLabel:     null, wEtaVal:    null,
    wDiv1:         null, wDiv2:      null, wDiv3: null,
    wNoNav:        null,
    screenW:       432,
    screenH:       480
  },

  onInit() {
    try {
      const info = getDeviceInfo()
      if (info && info.width)  this.state.screenW = info.width
      if (info && info.height) this.state.screenH = info.height
    } catch (e) {}

    // Swipe up → back to main nav page
    onGesture({
      callback: (event) => {
        if (event === GESTURE_UP) {
          back()
          return true // preventDefault
        }
        return false
      }
    })

    this.state.timerId = setInterval(() => {
      this.updateUi()
    }, 500)
  },

  build() {
    const W = this.state.screenW
    const H = this.state.screenH

    // ── Fill background ──────────────────────────────────────────
    createWidget(widget.FILL_RECT, {
      x: 0, y: 0, w: W, h: H,
      color: C_BG,
      radius: 0
    })

    // ── Full navigation phrase (top third) ───────────────────────
    // Swipe-up hint label
    createWidget(widget.TEXT, {
      x: 0, y: 18, w: W, h: 26,
      color: C_LABEL,
      text_size: 22,
      align_h: align.CENTER_H,
      text: '▲  NAVIGATION DETAILS'
    })

    this.state.wPhrase = createWidget(widget.TEXT, {
      x: 24, y: 52, w: W - 48, h: 130,
      color: C_PHRASE,
      text_size: 38,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text_style: text_style.WRAP,
      text: '--'
    })

    // ── Divider 1 ────────────────────────────────────────────────
    this.state.wDiv1 = createWidget(widget.FILL_RECT, {
      x: 28, y: 186, w: W - 56, h: 1,
      color: C_DIVIDER
    })

    // ── Time Remaining block ─────────────────────────────────────
    createWidget(widget.TEXT, {
      x: 0, y: 200, w: W / 2 - 4, h: 26,
      color: C_LABEL,
      text_size: 20,
      align_h: align.CENTER_H,
      text: 'TIME LEFT'
    })

    this.state.wTimeVal = createWidget(widget.TEXT, {
      x: 0, y: 228, w: W / 2 - 4, h: 68,
      color: C_TIME_VAL,
      text_size: 52,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: '--'
    })

    // Vertical separator between TIME and DIST
    createWidget(widget.FILL_RECT, {
      x: Math.floor(W / 2) - 1, y: 196, w: 1, h: 110,
      color: C_DIVIDER
    })

    // ── Distance Remaining block ─────────────────────────────────
    createWidget(widget.TEXT, {
      x: W / 2 + 4, y: 200, w: W / 2 - 28, h: 26,
      color: C_LABEL,
      text_size: 20,
      align_h: align.CENTER_H,
      text: 'DISTANCE'
    })

    this.state.wDistVal = createWidget(widget.TEXT, {
      x: W / 2 + 4, y: 228, w: W / 2 - 28, h: 68,
      color: C_DIST_VAL,
      text_size: 42,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text_style: text_style.WRAP,
      text: '--'
    })

    // ── Divider 2 ────────────────────────────────────────────────
    this.state.wDiv2 = createWidget(widget.FILL_RECT, {
      x: 28, y: 310, w: W - 56, h: 1,
      color: C_DIVIDER
    })

    // ── Arrival Time block (full width, prominent) ───────────────
    createWidget(widget.TEXT, {
      x: 0, y: 322, w: W, h: 26,
      color: C_LABEL,
      text_size: 20,
      align_h: align.CENTER_H,
      text: 'ARRIVAL'
    })

    this.state.wEtaVal = createWidget(widget.TEXT, {
      x: 0, y: 350, w: W, h: 88,
      color: C_ETA_VAL,
      text_size: 68,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: '--'
    })

    // ── "Not navigating" overlay (shown when no active nav) ──────
    this.state.wNoNav = createWidget(widget.TEXT, {
      x: 0, y: H / 2 - 40, w: W, h: 80,
      color: C_LABEL,
      text_size: 36,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text: 'Not navigating',
      visible: false
    })
  },

  updateUi() {
    try {
      const app   = getApp()
      const cache = (app && app._options && app._options.globalData)
        ? app._options.globalData.lastNavData
        : null
      if (!cache) return

      const active = (
        cache.status === 'Navigating' ||
        cache.status === 'Connected' && cache.street !== 'Not navigating' && cache.street !== ''
      ) && cache.street !== 'Not navigating' && cache.street !== ''

      if (!active) {
        this.state.wNoNav.setProperty(prop.VISIBLE, true)
        this.state.wPhrase.setProperty(prop.VISIBLE, false)
        this.state.wTimeVal.setProperty(prop.VISIBLE, false)
        this.state.wDistVal.setProperty(prop.VISIBLE, false)
        this.state.wEtaVal.setProperty(prop.VISIBLE, false)
        return
      }

      this.state.wNoNav.setProperty(prop.VISIBLE, false)
      this.state.wPhrase.setProperty(prop.VISIBLE, true)
      this.state.wTimeVal.setProperty(prop.VISIBLE, true)
      this.state.wDistVal.setProperty(prop.VISIBLE, true)
      this.state.wEtaVal.setProperty(prop.VISIBLE, true)

      // Full phrase: prefer cache.phrase, fall back to cache.street
      const phrase = (cache.phrase && cache.phrase.length > 0)
        ? String(cache.phrase)
        : String(cache.street || '--')
      this.state.wPhrase.setProperty(prop.TEXT, phrase)

      this.state.wTimeVal.setProperty(prop.TEXT,
        cache.timeRemaining && cache.timeRemaining !== '--'
          ? String(cache.timeRemaining) : '--')

      this.state.wDistVal.setProperty(prop.TEXT,
        cache.distanceRemaining && cache.distanceRemaining !== '--'
          ? String(cache.distanceRemaining) : '--')

      this.state.wEtaVal.setProperty(prop.TEXT,
        cache.arrivalTime && cache.arrivalTime !== '--'
          ? String(cache.arrivalTime) : '--')

    } catch (err) {
      console.log('[Details] UI update error:', err)
    }
  },

  onDestroy() {
    if (this.state.timerId) clearInterval(this.state.timerId)
    try { offGesture() } catch (e) {}
  }
})

import { createWidget, widget, prop, align, text_style } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'

const SPRITE_CELL_SIZE = 150

const ICON_MAP = {
  'u-turn-right': 0,          'u-turn-left': 1,           'roundabout-right': 2,      'roundabout-left': 3,
  'destination': 4,           'arrive': 4,                'straight': 5,              'continue': 5,
  'slight-left': 6,           'slight-right': 7,
  'fork-left': 8,             'fork-right': 9,            'turn-left': 10,            'turn-right': 11,
  'roundabout-exit-left': 12, 'roundabout-exit-right': 13,'merge': 14,                'roundabout-straight': 15
}

Page({
  state: {
    distanceWidget: null,
    streetWidget: null,
    statusWidget: null,
    subStatusWidget: null,
    iconWidget: null,
    timerId: null,
    screenWidth: 432,
    iconShowY: 100
  },

  onInit() {
    try {
      const info = getDeviceInfo()
      if (info && info.width) this.state.screenWidth = info.width
    } catch (e) {}

    this.state.timerId = setInterval(() => {
      this.updateUiFromCache()
    }, 500)
  },

  build() {
    const screenWidth = this.state.screenWidth
    const screenHeight = getDeviceInfo().height || 514

    // Vertical layout: icon sits toward the upper-middle, distance/street
    // form a tight block right below it, closer to screen center
    const iconY = Math.round(screenHeight * 0.18)
    const distanceY = iconY + SPRITE_CELL_SIZE + 5
    const streetY = distanceY + 60

    this.state.iconShowY = iconY

    this.state.statusWidget = createWidget(widget.TEXT, {
      x: 0, y: (screenHeight / 2) - 45, w: screenWidth, h: 80,
                                           color: 0xAAAAAA, text_size: 58, align_h: align.CENTER_H, align_v: align.CENTER_V, text: 'Connecting...'
    })

    this.state.subStatusWidget = createWidget(widget.TEXT, {
      x: 0, y: (screenHeight / 2) + 45, w: screenWidth, h: 50,
                                              color: 0xFFFFFF, text_size: 32, align_h: align.CENTER_H, align_v: align.CENTER_V, text: '', visible: false
    })

    // Pre-create the icon widget OFF-SCREEN to avoid visibility toggle bugs
    this.state.iconWidget = createWidget(widget.IMG, {
      x: (screenWidth - SPRITE_CELL_SIZE) / 2,
                                         y: -500, // Hidden initially
                                         w: SPRITE_CELL_SIZE,
                                         h: SPRITE_CELL_SIZE,
                                         src: 'icon_5.png' // Default to straight arrow
    })

    this.state.distanceWidget = createWidget(widget.TEXT, {
      x: 10, y: distanceY, w: screenWidth - 20, h: 65,
      color: 0x4A90E2, text_size: 54, align_h: align.CENTER_H, text: '--', visible: false
    })

    this.state.streetWidget = createWidget(widget.TEXT, {
      x: 20, y: streetY, w: screenWidth - 40, h: 110,
      color: 0xFFFFFF, text_size: 38, align_h: align.CENTER_H, align_v: align.CENTER_V,
      text_style: text_style.WRAP, text: '', visible: false
    })
  },

  updateUiFromCache() {
    try {
      const app = getApp()
      const cache = (app && app._options && app._options.globalData) ? app._options.globalData.lastNavData : null
      if (!cache) return

        const hasValidData = (cache.distance !== '--' && cache.street !== 'Not navigating' && cache.street !== '')

        this.state.distanceWidget.setProperty(prop.VISIBLE, hasValidData)
        this.state.streetWidget.setProperty(prop.VISIBLE, hasValidData)
        this.state.statusWidget.setProperty(prop.VISIBLE, !hasValidData)
        this.state.subStatusWidget.setProperty(prop.VISIBLE, !hasValidData)

        if (hasValidData) {
          this.state.distanceWidget.setProperty(prop.TEXT, String(cache.distance))
          this.state.streetWidget.setProperty(prop.TEXT, String(cache.street))

          let iconIndex = 5 // Default fallback is 'straight' (5)

if (cache.icon !== undefined && cache.icon !== null) {
  if (typeof cache.icon === 'string') {
    const normalizedIcon = cache.icon.toLowerCase().replace(/_/g, '-')
    iconIndex = ICON_MAP[normalizedIcon] !== undefined ? ICON_MAP[normalizedIcon] : 5
  } else if (typeof cache.icon === 'number') {
    iconIndex = cache.icon
  }
}

const targetSrc = `icon_${iconIndex}.png`

// Explicitly set the source, then bring it on-screen
this.state.iconWidget.setProperty(prop.SRC, targetSrc)
this.state.iconWidget.setProperty(prop.Y, this.state.iconShowY)

        } else {
          // Move off-screen to hide
          this.state.iconWidget.setProperty(prop.Y, -500)

          if (cache.status === 'Connected') {
            this.state.statusWidget.setProperty(prop.COLOR, 0x4A90E2)
            this.state.statusWidget.setProperty(prop.TEXT, 'Connected')
            this.state.subStatusWidget.setProperty(prop.TEXT, 'Start navigating')
          } else {
            this.state.statusWidget.setProperty(prop.COLOR, 0xAAAAAA)
            this.state.statusWidget.setProperty(prop.TEXT, 'Connecting...')
            this.state.subStatusWidget.setProperty(prop.TEXT, '')
          }
        }
    } catch (err) {
      console.log('UI update error:', err)
    }
  },

  onDestroy() {
    if (this.state.timerId) clearInterval(this.state.timerId)
  }
})

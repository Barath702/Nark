import './shared/device-polyfill'
import { MessageBuilder } from './shared/message'
import { getPackageInfo } from '@zos/app'
import * as ble from '@zos/ble'

App({
  globalData: {
    messageBuilder: null,
    lastNavData: {
      distance: '--',
      street: 'Waiting for data...',
      icon: 0,                  // nav-arrow icon index
      status: 'Connecting...',
      phrase: '',               // full instruction e.g. "Turn left onto MG Road"
      arrivalTime: '--'         // 24-hour arrival e.g. "21:52"
    }
  },

  onCreate() {
    console.log('App: onCreate called')
    const appInstance = this

    try {
      const { appId } = getPackageInfo()

      const messageBuilder = new MessageBuilder({
        appId,
        appDevicePort: 20,
        appSidePort: 0,
        ble
      })

      this.globalData.messageBuilder = messageBuilder
      messageBuilder.connect()
      console.log('App: MessageBuilder connected')

      messageBuilder.on('call', ({ payload: buf }) => {
        console.log('App: Received call event')
        try {
          const data = messageBuilder.buf2Json(buf)
          console.log('App: Parsed data ->', JSON.stringify(data))

          if (data) {
            const prev = appInstance.globalData.lastNavData
            appInstance.globalData.lastNavData = {
              distance:    data.distance    !== undefined ? data.distance    : prev.distance,
              street:      data.street      !== undefined ? data.street      : prev.street,
              icon:        data.icon        !== undefined ? data.icon        : prev.icon,
              status:      data.status      !== undefined ? data.status      : 'Connected',
              phrase:      data.phrase      !== undefined ? data.phrase      : prev.phrase,
              arrivalTime: data.arrivalTime !== undefined ? data.arrivalTime : prev.arrivalTime
            }
          }
        } catch (err) {
          console.log('App: Parse error ->', err)
        }
      })
    } catch (e) {
      console.log('App: Initialization error ->', e)
    }
  },

  onDestroy() {
    console.log('App: onDestroy called')
    if (this.globalData.messageBuilder) {
      this.globalData.messageBuilder.disConnect()
    }
  }
})

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
      icon: 0, // Initialize with default position frame
      status: 'Connecting...'
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
            appInstance.globalData.lastNavData = {
              distance: data.distance || appInstance.globalData.lastNavData.distance,
              street: data.street || appInstance.globalData.lastNavData.street,
              icon: data.icon !== undefined ? data.icon : appInstance.globalData.lastNavData.icon,
              status: 'Connected'
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

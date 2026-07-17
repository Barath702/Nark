import { MessageBuilder } from '../shared/message'
import { getPackageInfo } from '@zos/app'
import * as ble from '@zos/ble'
import { LocalStorage } from '@zos/storage'

let localStorage
let messageBuilder

AppService({
    onInit(param) {
        console.log('[AppService] onInit ->', param)

        try {
            localStorage = new LocalStorage()
            const packageInfo = getPackageInfo()
            console.log('[AppService] packageInfo ->', JSON.stringify(packageInfo))
            const appId = packageInfo ? packageInfo.appId : 27208

            messageBuilder = new MessageBuilder({
                appId,
                appDevicePort: 20,
                appSidePort: 0,
                ble
            })

            // Write startup status to verify LocalStorage is accessible and background service is alive
            const startupData = {
                distance: '--',
                street: 'Searching connection...',
                icon: 0,
                status: 'Connected',
                phrase: '',
                arrivalTime: '--',
                updatedAt: Date.now()
            }
            localStorage.setItem('lastNavData', JSON.stringify(startupData))
            console.log('[AppService] Stored startupData successfully')

            messageBuilder.connect()
            console.log('[AppService] MessageBuilder connected')

            messageBuilder.on('call', ({ payload: buf }) => {
                console.log('[AppService] Received BLE message, payload buffer length:', buf.length)

                try {
                    const data = messageBuilder.buf2Json(buf)
                    console.log('[AppService] Parsed BLE data:', JSON.stringify(data))

                    if (data) {
                        // Detailed field validation
                        console.log('[AppService] Data fields:', {
                            distance: data.distance,
                            street: data.street,
                            icon: data.icon,
                            status: data.status
                        })

                        const navData = {
                            distance:    data.distance    !== undefined ? String(data.distance)    : '--',
                            street:      data.street      !== undefined ? String(data.street)      : 'Not navigating',
                            icon:        data.icon        !== undefined ? data.icon                : 0,
                            status:      'Connected',
                            phrase:      data.phrase      !== undefined ? String(data.phrase)      : '',
                            arrivalTime: data.arrivalTime !== undefined ? String(data.arrivalTime) : '--',
                            updatedAt: Date.now()
                        }

                        console.log('[AppService] About to store navData:', JSON.stringify(navData))

                        // Store in localStorage
                        localStorage.setItem('lastNavData', JSON.stringify(navData))
                        console.log('[AppService] Stored successfully')

                        // Verify it was stored correctly
                        const verify = localStorage.getItem('lastNavData', null)
                        console.log('[AppService] Verification read from storage:', verify)

                        if (verify !== JSON.stringify(navData)) {
                            console.log('[AppService] WARNING: Stored data does not match what was written!')
                            console.log('[AppService] Expected:', JSON.stringify(navData))
                            console.log('[AppService] Got:', verify)
                        }
                    } else {
                        console.log('[AppService] Received null/empty data object')
                    }
                } catch (err) {
                    console.log('[AppService] BLE parse error:', err)
                    console.log('[AppService] Error details:', err.message || 'No message')
                    console.log('[AppService] Buffer content (first 50 bytes):', buf.slice(0, 50))
                }
            })

            // Also listen for errors
            messageBuilder.on('error', (err) => {
                console.log('[AppService] MessageBuilder error:', err)
            })

        } catch (e) {
            console.log('[AppService] init error ->', e)
            console.log('[AppService] Init error details:', e.message || 'No message')
        }
    },

    onDestroy() {
        console.log('[AppService] onDestroy called')
        try {
            if (messageBuilder) {
                messageBuilder.disConnect()
                console.log('[AppService] MessageBuilder disconnected')
            }
        } catch (e) {
            console.log('[AppService] disconnect error ->', e)
        }
    }
})

import { MessageBuilder } from '../shared/message-side'

const messageBuilder = new MessageBuilder()
const SERVER_URL = 'http://192.168.0.7:3000/getlatestmaps'
const POLL_INTERVAL = 2000

let isFetching = false

function fetchMapsData() {
  if (isFetching) return
    isFetching = true

    fetch({
      url: SERVER_URL,
      method: 'GET'
    })
    .then((response) => {
      if (!response || !response.body) {
        messageBuilder.call({ distance: '--', street: 'Not navigating', icon: 0, status: 'Idle' })
        return
      }

      let responseData
      try {
        responseData = typeof response.body === 'string' ? JSON.parse(response.body) : response.body
      } catch (e) {
        console.log('Failed to parse server body:', response.body)
        return
      }

      if (!responseData.distance && !responseData.street) {
        console.log('No active navigation, sending idle signal to watch')
        messageBuilder.call({ distance: '--', street: 'Not navigating', icon: 0, status: 'Connected' })
        return
      }

      console.log('Fetched data successfully, sending to watch...')
      messageBuilder.call({
        distance: responseData.distance,
        street: responseData.street,
        icon: responseData.icon, // Captures either index number (0-15) or direction keyword string
                          status: 'Navigating'
      })
    })
    .catch((err) => {
      console.log('Network/Fetch failed:', err)
      messageBuilder.call({ distance: '--', street: 'Disconnected', icon: 0, status: 'Error' })
    })
    .finally(() => {
      isFetching = false
    })
}

AppSideService({
  onInit() {
    console.log('Companion Service started successfully')
    messageBuilder.listen(() => {
      console.log('Side service message channel is listening')
    })
    this.timer = setInterval(fetchMapsData, POLL_INTERVAL)
  },

  onDestroy() {
    if (this.timer) clearInterval(this.timer)
  }
})

import { MessageBuilder } from '../shared/message-side'

const messageBuilder = new MessageBuilder()
const SERVER_URL = 'http://127.0.0.1:3000/getlatestmaps'
const POLL_INTERVAL = 1000

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
        messageBuilder.call({
          distance: '--',
          street: 'Not navigating',
          icon: 0,
          phrase: '',
          arrivalTime: '--',
          status: 'Idle'
        })
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
        messageBuilder.call({
          distance: '--',
          street: 'Not navigating',
          icon: 0,
          phrase: '',
          arrivalTime: '--',
          status: 'Connected'
        })
        return
      }

      console.log('Fetched data successfully, sending to watch...')
      messageBuilder.call({
        distance:    responseData.distance,
        street:      responseData.street,
        icon:        responseData.icon,
        // phrase: full instruction text; fall back to street if absent
        phrase:      responseData.phrase      || responseData.street || '',
        // arrivalTime must be 24-hour "HH:MM" e.g. "21:52"
        arrivalTime: responseData.arrivalTime || '--',
        status: 'Navigating'
      })
    })
    .catch((err) => {
      console.log('Network/Fetch failed:', err)
      messageBuilder.call({
        distance: '--',
        street: 'Disconnected',
        icon: 0,
        phrase: '',
        arrivalTime: '--',
        status: 'Error'
      })
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

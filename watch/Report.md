# Nark Watch App - Data Flow Debugging

## Current Data Flow Status

✅ **Phone → Server**: Mobile app sending navigation data to 192.168.0.7:3000/getlatestmaps
✅ **Watch ← Phone (BLE)**: AppService receiving BLE messages and storing in localStorage
❌ **Page Display**: distanceWidget and streetWidget NOT showing data

## The Problem

Looking at your **page/index.js** (document index 1), the issue is here:

```javascript
const hasValidData = (cache.distance !== '--' && cache.street !== 'Not navigating' && cache.street !== '')

this.state.distanceWidget.setProperty(prop.VISIBLE, hasValidData)
this.state.streetWidget.setProperty(prop.VISIBLE, hasValidData)
```

This logic checks if data exists, BUT there's a timing issue:

1. Page calls `updateUiFromCache()` every 500ms
2. AppService stores data with: `distance: data.distance || '--'`
3. If the server sends `"distance": "0 m"` → it stores correctly
4. But the check `cache.distance !== '--'` should pass... 

**Possible causes:**

### Cause 1: AppService data never reaches localStorage
- The BLE message arrives but `buf2Json()` fails silently
- Data is received but not stringified properly in localStorage.setItem()

### Cause 2: Page can't read localStorage (permission issue - though you fixed this)
- Try-catch around localStorage.getItem() might be swallowing errors

### Cause 3: Data format mismatch
- Server sends: `{"distance": "0 m", "street": "Head southeast", "icon": "straight"}`
- AppService receives and stores it
- Page retrieves it but the `cache` object is null or malformed

### Cause 4: Multiple onInit() calls
- Zepp OS calls onInit() on every screen wake
- Each time it creates NEW widget references
- But the old widgets are still in memory (memory leak noted in your Nark project)
- Page tries to update old widget refs that don't exist anymore

## What to Check Next

1. **Add detailed logging** to page updateUiFromCache():
   ```javascript
   const raw = localStorage.getItem('lastNavData', null)
   console.log('Raw localStorage:', raw)  // What's actually stored?
   const cache = raw ? JSON.parse(raw) : null
   console.log('Parsed cache:', JSON.stringify(cache))  // What was parsed?
   ```

2. **Check what AppService is actually receiving**
   - Add logging in AppService: `console.log('Received BLE data:', JSON.stringify(data))`
   - Verify the phone is actually sending data over BLE

3. **Verify storage before/after setItem()**
   ```javascript
   // In AppService before storing:
   console.log('About to store:', JSON.stringify(navData))
   localStorage.setItem('lastNavData', JSON.stringify(navData))
   
   // Then immediately read it back:
   const verify = localStorage.getItem('lastNavData', null)
   console.log('Verified stored:', verify)
   ```

4. **Check screen wake behavior**
   - Add `onShow()` and `onHide()` to page to track when page is displayed
   - See if widgets are being recreated

import React from 'react';
import Quote from './components/Quote';
import Clock from './components/Clock';
import Weather from './components/Weather';
import SpotifyNow from './components/SpotifyNow';
import News from './components/News';
import Crypto from './components/Crypto';

function App() {
  return (
    <div className="min-h-screen bg-black p-4 flex flex-col space-y-6">
      <Quote />
      <div className="relative">
        <Clock />
        <Weather />
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <SpotifyNow />
        <Crypto />
        <News category="general" />
        <News category="gaming" />
        <News category="sports" />
        <News category="entertainment" />
      </div>
    </div>
  );
}

export default App;

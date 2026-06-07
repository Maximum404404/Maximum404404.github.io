import React, { useEffect, useState } from 'react';
import axios from 'axios';

const Crypto = () => {
  const [prices, setPrices] = useState({});

  useEffect(() => {
    axios.get('/api/crypto')
      .then(res => setPrices(res.data))
      .catch(console.error);
  }, []);

  return (
    <div className="p-4 bg-gray-800 text-white rounded-lg">
      <h2 className="text-lg font-bold">Crypto Prices (USD)</h2>
      <p>BTC: ${prices.bitcoin?.usd}</p>
      <p>ETH: ${prices.ethereum?.usd}</p>
    </div>
  );
};

export default Crypto;

import React, { useEffect, useState } from 'react';
import axios from 'axios';

const Quote = () => {
  const [quote, setQuote] = useState('');

  useEffect(() => {
    axios.get('https://api.quotable.io/random')
      .then(res => setQuote(res.data.content));
  }, []);

  return (
    <div className="text-white text-center text-lg p-4 bg-gray-900">
      <p>"{quote}"</p>
    </div>
  );
};

export default Quote;

import React, { useEffect, useState } from 'react';
import axios from 'axios';

const News = ({ category }) => {
  const [headlines, setHeadlines] = useState([]);

  useEffect(() => {
    axios.get(`/api/news?category=${category}`)
      .then(res => setHeadlines(res.data))
      .catch(console.error);
  }, [category]);

  return (
    <div className="p-4 bg-gray-900 text-white rounded-lg">
      <h2 className="text-xl font-bold capitalize">{category} News</h2>
      <ul className="list-disc list-inside mt-2 space-y-1">
        {headlines.map((item, i) =>
          <li key={i}>
            <a href={item.link} target="_blank" rel="noopener noreferrer" className="hover:underline">
              {item.title}
            </a>
          </li>
        )}
      </ul>
    </div>
  );
};

export default News;

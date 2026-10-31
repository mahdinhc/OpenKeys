import React from 'react';
import Svg, {Path} from 'react-native-svg';
import data from './icons.json';

const D: any = data;

export const Icon = ({name, size = 24, color = '#e8eaed'}: {name: string; size?: number; color?: string}) => {
  const d = D.icons[name];
  if (!d) return null;
  const outline = D.outline.includes(name);
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      <Path
        d={d} fillRule="evenodd"
        fill={outline ? 'none' : color}
        stroke={outline ? color : 'none'} strokeWidth={outline ? 1.9 : 0} strokeLinejoin="round"
      />
    </Svg>
  );
};

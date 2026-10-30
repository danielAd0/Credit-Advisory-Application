import {useMemo} from 'react';

const CX = 200;
const CY = 210;
const point = (r: number, deg: number) => [CX + r * Math.cos((deg * Math.PI) / 180), CY - r * Math.sin((deg * Math.PI) / 180)] as const;


export default function LoginGauge(){

    const {ticks, arc, needle} = useMemo ( () => {

        const ticks = Array.from({length : 41}, (_ , i) => {
            const deg = 200 - i * 5.5;
            const major = i % 5 === 0;
            const [x1 , y1] = point(150 , deg);
            const [x2 , y2] = point(major ? 128 : 139 , deg);
            return {i , x1 , y1 , x2 , y2 , major , on : i <= 29};
        });

        const [sx , sy] = point(104 , 200);
        const [ex , ey] = point(104 , -204);
        const [nx, ny] = point(112, 200);
        return { ticks, arc: `M ${sx} ${sy} A 104 104 0 1 1 ${ex} ${ey}`, needle: { nx, ny } };
    },[]);

    return (
           <svg className="lg-gauge" viewBox="0 0 400 284" aria-hidden="true" focusable="false">
                <path className="lg-track" d={arc} />
                {ticks.map((t) => (
                 <line
                    key={t.i}
                    className={`lg-tick${t.major ? ' major' : ''}${t.on ? ' on' : ''}`}
                    x1={t.x1} y1={t.y1} x2={t.x2} y2={t.y2}
                />
            ))}

            <g className="lg-needle">
                <line x1={CX} y1={CY} x2={needle.nx} y2={needle.ny} />
            </g>

            <circle className="lg-hub" cx={CX} cy={CY} r="9" />
            <circle className="lg-hub-core" cx={CX} cy={CY} r="3.5" />

           </svg>
        );

}

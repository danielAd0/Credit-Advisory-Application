/** "12 consultants", "1 product". */
export const countOf = (n: number, one: string, many = `${one}s`) =>
  `${n.toLocaleString('en-ZA')} ${n === 1 ? one : many}`;

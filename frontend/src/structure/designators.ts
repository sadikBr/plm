/** R1, R2, R3, R7 → "R1-R3, R7" (roadmap 4.5). */
export function formatDesignators(list: string[]): string {
  const parts: string[] = [];
  const split = list.map((d) => {
    const m = /^(.*?)(\d+)$/.exec(d);
    return m ? { d, p: m[1]!, n: Number(m[2]) } : { d, p: d, n: -1 };
  });
  let i = 0;
  while (i < split.length) {
    let j = i;
    while (split[i]!.n >= 0 && j + 1 < split.length && split[j + 1]!.p === split[i]!.p && split[j + 1]!.n === split[j]!.n + 1) j++;
    parts.push(
      j - i >= 2
        ? `${split[i]!.d}-${split[j]!.d}`
        : split
            .slice(i, j + 1)
            .map((x) => x.d)
            .join(", "),
    );
    i = j + 1;
  }
  return parts.join(", ");
}

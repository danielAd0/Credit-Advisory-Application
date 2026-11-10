import type { ReactNode, SVGProps } from 'react';

/** Small outline icon set (24px grid, 1.75 stroke) used by the shell and dashboard. */
function Svg({ children, ...rest }: SVGProps<SVGSVGElement> & { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

type P = SVGProps<SVGSVGElement>;

export const IconGrid = (p: P) => (
  <Svg {...p}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="1.75" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="1.75" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1.75" />
    <rect x="13.5" y="13.5" width="7" height="7" rx="1.75" />
  </Svg>
);

export const IconUsers = (p: P) => (
  <Svg {...p}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M2.5 20a6.5 6.5 0 0 1 13 0" />
    <path d="M15.5 4.6a3.5 3.5 0 0 1 0 6.8" />
    <path d="M18 14.2a6.5 6.5 0 0 1 3.5 5.8" />
  </Svg>
);

export const IconUserCheck = (p: P) => (
  <Svg {...p}>
    <circle cx="9" cy="8" r="3.5" />
    <path d="M2.5 20a6.5 6.5 0 0 1 11.2-4.5" />
    <path d="m15.5 18 2 2 4-4.5" />
  </Svg>
);

export const IconChart = (p: P) => (
  <Svg {...p}>
    <path d="M3.5 3.5v17h17" />
    <path d="M8 16.5v-5M12 16.5v-9M16 16.5v-6.5M20 16.5V9" />
  </Svg>
);

export const IconTeam = (p: P) => (
  <Svg {...p}>
    <circle cx="9" cy="8.5" r="3.5" />
    <path d="M2.5 20.5a6.5 6.5 0 0 1 13 0" />
    <circle cx="17.5" cy="6.5" r="2.5" />
    <path d="M17 12.5a5 5 0 0 1 4.5 5" />
  </Svg>
);

export const IconFolder = (p: P) => (
  <Svg {...p}>
    <path d="M3.5 7a2 2 0 0 1 2-2h3.8l2 2.5h7.2a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" />
  </Svg>
);

export const IconBriefcase = (p: P) => (
  <Svg {...p}>
    <rect x="3" y="7" width="18" height="13" rx="2" />
    <path d="M8.5 7V5.5a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2V7" />
    <path d="M3 12.5h18" />
  </Svg>
);

export const IconQuote = (p: P) => (
  <Svg {...p}>
    <path d="M6 3.5h9l4 4V20.5H6Z" />
    <path d="M14.5 3.5v4.5H19" />
    <path d="M9 13h7M9 16.5h5" />
  </Svg>
);

export const IconFile = (p: P) => (
  <Svg {...p}>
    <path d="M6 3.5h8.5l4 4V20.5H6Z" />
    <path d="M14 3.5V8h4.5" />
    <path d="M9 12.5h6M9 16h6" />
  </Svg>
);

export const IconSwap = (p: P) => (
  <Svg {...p}>
    <path d="M4 8h15.5M15.5 4l4 4-4 4" />
    <path d="M20 16H4.5M8.5 12l-4 4 4 4" />
  </Svg>
);

export const IconDownload = (p: P) => (
  <Svg {...p}>
    <path d="M12 3.5v12M7 10.5l5 5 5-5" />
    <path d="M4 15.5v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" />
  </Svg>
);

export const IconChevronDown = (p: P) => (
  <Svg {...p}>
    <path d="m6 9 6 6 6-6" />
  </Svg>
);

export const IconArrowUpRight = (p: P) => (
  <Svg {...p}>
    <path d="M7 17 17 7M8.5 7H17v8.5" />
  </Svg>
);

export const IconArrowRight = (p: P) => (
  <Svg {...p}>
    <path d="M4.5 12h15M13.5 6l6 6-6 6" />
  </Svg>
);

export const IconCheck = (p: P) => (
  <Svg {...p}>
    <path d="m5 12.5 4.5 4.5L19 7.5" />
  </Svg>
);

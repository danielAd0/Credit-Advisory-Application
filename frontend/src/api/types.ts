export type Role = 'ADMIN' | 'MANAGER' | 'CONSULTANT';
export type ConsultantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

export interface Session{
  username : string;
  role : Role;
  mustChangePassword : boolean;
  csrf: {headerName : string; token : string};
}

export interface ClientSummary{
  id : string;
  fullName : string;
  idNumberMasked : string;
  consultantName : string | null;
  openCases : number;
  updatedAt : string;
}

export interface ConsultantSummary{
  id : string
  fullName: string;
  username : string;
  role : Role;
  status : ConsultantStatus;
  clientCount : number;
}

// Program : SeikaDevice.dll
// Language: x86:LE:32:default
// Functions total: 110

// ===================== FUN_10001000 @ 10001000 =====================

undefined4 FUN_10001000(void)

{
  return 1;
}



// ===================== BrailleOpen @ 10001010 =====================

bool __cdecl BrailleOpen(int param_1,undefined4 param_2)

{
  char cVar1;
  
                    /* 0x1010  3  BrailleOpen */
  if (param_1 == 0) {
    DAT_10005558 = 1;
    DAT_10005560 = param_2;
    cVar1 = FUN_10001530();
    return cVar1 != '\0';
  }
  if (param_1 == 2) {
    DAT_10005558 = param_1;
    DAT_10005560 = param_2;
    cVar1 = FUN_10001530();
    return cVar1 != '\0';
  }
  DAT_10005560 = param_2;
  DAT_10005558 = (param_1 != 6) - 1 & 3;
  cVar1 = FUN_10001530();
  return cVar1 != '\0';
}



// ===================== BrailleOpen2 @ 10001080 =====================

/* bool __cdecl BrailleOpen2(struct HWND__ *,int,int) */

bool __cdecl BrailleOpen2(HWND__ *param_1,int param_2,int param_3)

{
  bool bVar1;
  
                    /* 0x1080  1  ?BrailleOpen2@@YA_NPAUHWND__@@HH@Z */
  DAT_10005980 = param_1;
  bVar1 = BrailleOpen(param_2,param_3);
  return bVar1;
}



// ===================== BrailleOpen2 @ 100010a0 =====================

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void __cdecl BrailleOpen2(undefined4 param_1,undefined4 param_2,int param_3,undefined4 param_4)

{
                    /* 0x10a0  4  BrailleOpen2 */
  DAT_10005980 = param_1;
  _DAT_10005984 = param_2;
  BrailleOpen(param_3,param_4);
  return;
}



// ===================== GetBrailleKey @ 100010d0 =====================

undefined4 __cdecl GetBrailleKey(undefined4 *param_1,undefined4 *param_2)

{
  undefined4 uVar1;
  
                    /* 0x10d0  6  GetBrailleKey */
  uVar1 = FUN_100024a0();
  if ((char)uVar1 == '\0') {
    return uVar1;
  }
  if (param_1 != (undefined4 *)0x0) {
    *param_1 = DAT_10005564;
  }
  if (param_2 != (undefined4 *)0x0) {
    *param_2 = DAT_10005568;
  }
  return CONCAT31((int3)((uint)param_2 >> 8),1);
}



// ===================== GetBrailleDisplayInfo @ 10001110 =====================

undefined4 __cdecl GetBrailleDisplayInfo(undefined4 *param_1,undefined4 *param_2)

{
                    /* 0x1110  5  GetBrailleDisplayInfo */
  if (param_1 != (undefined4 *)0x0) {
    *param_1 = DAT_1000592c;
  }
  if (param_2 != (undefined4 *)0x0) {
    *param_2 = DAT_10005928;
  }
  return CONCAT31((int3)((uint)param_2 >> 8),1);
}



// ===================== UpdateBrailleDisplay @ 10001140 =====================

undefined4 __cdecl UpdateBrailleDisplay(int param_1,int param_2)

{
  int iVar1;
  DWORD DVar2;
  undefined4 uVar3;
  int unaff_ESI;
  DWORD DVar4;
  undefined4 local_4;
  
                    /* 0x1140  7  UpdateBrailleDisplay */
  iVar1 = 0;
  DVar4 = DAT_10005920;
  if (DAT_1000592c != 0) {
    do {
      if (DAT_10005924 != '\0') {
        (&DAT_1000581d)[DVar4] = 0;
        DVar4 = DVar4 + 1;
      }
      if ((iVar1 < param_2) && (param_1 != 0)) {
        (&DAT_1000581d)[DVar4] = *(undefined1 *)(iVar1 + param_1);
      }
      else {
        (&DAT_1000581d)[DVar4] = 0;
      }
      iVar1 = iVar1 + 1;
      DVar4 = DVar4 + 1;
    } while (DAT_1000592c != iVar1);
  }
  if (DAT_10005558 == 2) {
    local_4 = 0;
    DVar2 = 0;
    if ((DAT_10005978 != 0) && (DVar2 = HidUart_IsOpened(DAT_10005978,&local_4), unaff_ESI != 0)) {
      uVar3 = FUN_10002d60(0x10005968);
      return CONCAT31((int3)((uint)uVar3 >> 8),1);
    }
  }
  else {
    DVar2 = DAT_10005558;
    if ((DAT_10005558 == 1) && (DAT_10005964 != '\0')) {
      DVar2 = FUN_10002c10(&DAT_1000581d,DVar4);
    }
  }
  return CONCAT31((int3)(DVar2 >> 8),1);
}



// ===================== BrailleClose @ 10001200 =====================

undefined1 BrailleClose(void)

{
                    /* 0x1200  2  BrailleClose */
  FUN_10001460();
  return 1;
}



// ===================== FUN_10001210 @ 10001210 =====================

void __fastcall FUN_10001210(undefined1 *param_1)

{
  undefined1 *puVar1;
  int iVar2;
  
  param_1[0x4c] = 0;
  *(undefined4 *)(param_1 + 0x50) = 0;
  *(undefined4 *)(param_1 + 0x54) = 0;
  *(undefined4 *)(param_1 + 0x58) = 0;
  iVar2 = 4;
  puVar1 = param_1;
  do {
    *puVar1 = 0;
    puVar1 = puVar1 + 1;
    iVar2 = iVar2 + -1;
  } while (iVar2 != 0);
  iVar2 = 0x40;
  puVar1 = param_1 + 4;
  do {
    *puVar1 = 0;
    puVar1 = puVar1 + 1;
    iVar2 = iVar2 + -1;
  } while (iVar2 != 0);
  iVar2 = 8;
  puVar1 = param_1 + 0x44;
  do {
    *puVar1 = 0;
    puVar1 = puVar1 + 1;
    iVar2 = iVar2 + -1;
  } while (iVar2 != 0);
  return;
}



// ===================== FUN_10001260 @ 10001260 =====================

void __fastcall FUN_10001260(undefined2 *param_1)

{
  undefined1 uVar1;
  undefined2 uVar2;
  undefined2 *in_EAX;
  undefined1 *puVar3;
  int iVar4;
  
  uVar2 = *param_1;
  uVar1 = *(undefined1 *)(param_1 + 1);
  *(undefined4 *)(in_EAX + 0x28) = 3;
  *in_EAX = uVar2;
  *(undefined1 *)(in_EAX + 1) = uVar1;
  puVar3 = (undefined1 *)((int)in_EAX + 3);
  iVar4 = 1;
  do {
    *puVar3 = 0;
    iVar4 = iVar4 + -1;
    puVar3 = puVar3 + 1;
  } while (0 < iVar4);
  return;
}



// ===================== FUN_10001460 @ 10001460 =====================

void FUN_10001460(void)

{
  if (DAT_10005978 != 0) {
    HidUart_Close(DAT_10005978);
  }
  DAT_10005978 = 0;
  if ((DAT_10005964 != '\0') || (DAT_10005938 != (HANDLE)0x0)) {
    if (DAT_1000594c != (HANDLE)0x0) {
      CloseHandle(DAT_1000594c);
    }
    if (DAT_10005960 != (HANDLE)0x0) {
      CloseHandle(DAT_10005960);
    }
    if (DAT_10005938 != (HANDLE)0x0) {
      CloseHandle(DAT_10005938);
    }
    DAT_10005964 = '\0';
    DAT_10005938 = (HANDLE)0x0;
  }
  if (DAT_10005980 != 0) {
    if (DAT_10005710 != (HANDLE)0x0) {
      ResetEvent(DAT_10005718);
      PostThreadMessageA(DAT_10005714,0x12,0,0);
      WaitForSingleObject(DAT_10005718,2000);
      CloseHandle(DAT_10005710);
      DAT_10005710 = (HANDLE)0x0;
      DAT_10005714 = 0;
    }
    if (DAT_10005718 != (HANDLE)0x0) {
      CloseHandle(DAT_10005718);
      DAT_10005718 = (HANDLE)0x0;
    }
    DAT_10005980 = 0;
  }
  return;
}



// ===================== FUN_10001530 @ 10001530 =====================

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined1 FUN_10001530(void)

{
  char cVar1;
  int iVar2;
  
  if (DAT_10005558 == 2) {
    iVar2 = HidUart_GetNumDevices(&DAT_10005970,0,0);
    if (((iVar2 != 0) || (DAT_10005970 == 0)) || (cVar1 = FUN_10001d40(), cVar1 == '\0')) {
      if (DAT_10005978 != 0) {
        HidUart_Close(DAT_10005978);
      }
      DAT_10005978 = 0;
      DAT_1000592c = 0;
      DAT_10005930 = 0;
      DAT_10005928 = 0;
      _DAT_10005934 = 0;
      return 0;
    }
  }
  else {
    if (DAT_10005558 != 1) {
      return 0;
    }
    cVar1 = FUN_10002a30(DAT_10005560,DAT_100054d0);
    if ((cVar1 == '\0') || (cVar1 = FUN_10001d40(), cVar1 == '\0')) {
      FUN_10002bd0();
      DAT_1000592c = 0;
      DAT_10005930 = 0;
      DAT_10005928 = 0;
      _DAT_10005934 = 0;
      return 0;
    }
  }
  if (DAT_10005980 != 0) {
    FUN_10002930();
  }
  return 1;
}



// ===================== FUN_10001600 @ 10001600 =====================

uint __fastcall FUN_10001600(uint param_1,uint param_2,undefined4 param_3,uint param_4,uint param_5)

{
  uint in_EAX;
  uint uVar1;
  
  if (DAT_10005558 == 2) {
    uVar1 = FUN_10001920(param_4,param_5);
    return uVar1;
  }
  if (DAT_10005558 == 1) {
    uVar1 = FUN_10001660(param_4,(char)param_5,param_2,param_1,in_EAX);
    return uVar1;
  }
  return in_EAX & 0xffffff00;
}



// ===================== FUN_10001660 @ 10001660 =====================

/* WARNING: Removing unreachable block (ram,0x10001778) */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

uint FUN_10001660(int param_1,char param_2,uint param_3,uint param_4,uint param_5)

{
  char *pcVar1;
  char *in_EAX;
  DWORD DVar2;
  DWORD DVar3;
  char *pcVar4;
  int iVar5;
  LPCVOID unaff_EDI;
  undefined2 in_FPUControlWord;
  byte bVar6;
  double dVar7;
  undefined4 local_8;
  
  if (param_1 == 4) {
    iVar5 = 0;
    do {
      if (DAT_10005964 == '\0') break;
      FUN_10002c10(unaff_EDI,*(DWORD *)((int)unaff_EDI + 0x50));
      Sleep(500);
      in_EAX = (char *)FUN_10002cb0(&DAT_1000571c);
      bVar6 = (byte)((ushort)in_FPUControlWord >> 8);
      if ((6 < (int)in_EAX) &&
         (in_EAX = (char *)((int)unaff_EDI + 4), DAT_1000571c == *(char *)((int)unaff_EDI + 4))) {
        do {
          in_EAX = in_EAX + 1;
          if (2 < (int)(in_EAX + (-4 - (int)unaff_EDI))) {
            DAT_10005924 = *(undefined1 *)((int)unaff_EDI + 0x4c);
            DAT_10005920 = *(int *)((int)unaff_EDI + 0x58);
            iVar5 = 0;
            if (0 < DAT_10005920) {
              do {
                (&DAT_1000581d)[iVar5] = *(undefined1 *)((int)unaff_EDI + iVar5 + 0x44);
                iVar5 = iVar5 + 1;
              } while (iVar5 < DAT_10005920);
            }
            DAT_10005920 = DAT_10005920 + 1;
            (&DAT_1000581d)[iVar5] = DAT_10005721;
            DAT_10005928 = (uint)DAT_10005720;
            DAT_1000592c = (uint)DAT_10005721;
            DAT_10005930 = (uint)DAT_10005722;
            _DAT_1000555c = 4;
            dVar7 = ceil((double)DAT_10005928 * _DAT_10004340);
            local_8 = (undefined4)(longlong)ROUND(dVar7);
            _DAT_1000556c = local_8;
            dVar7 = (double)(int)DAT_10005930;
            if ((int)DAT_10005930 < 0) {
              dVar7 = dVar7 + _DAT_10004348;
            }
            dVar7 = ceil(dVar7 * _DAT_10004340);
            local_8 = (undefined4)(longlong)ROUND(dVar7);
            _DAT_10005570 = local_8;
            return CONCAT31(bVar6 | 0xc,1);
          }
        } while (in_EAX[(int)&DAT_10005718 - (int)unaff_EDI] == *in_EAX);
      }
      iVar5 = iVar5 + 1;
    } while (iVar5 < 3);
  }
  else {
    pcVar1 = (char *)0x0;
    while (DAT_10005964 != '\0') {
      FUN_10002c10(unaff_EDI,*(DWORD *)((int)unaff_EDI + 0x50));
      Sleep(500);
      DVar2 = FUN_10002cb0(&DAT_1000571c);
      if ((5 < (int)DVar2) && (pcVar4 = (char *)((int)unaff_EDI + 4), DAT_1000571c == *pcVar4)) {
        DVar3 = 0;
        do {
          DVar3 = DVar3 + 1;
          pcVar4 = pcVar4 + 1;
          if (((int)DVar2 <= (int)DVar3) || (0x3f < (int)DVar3)) break;
        } while (pcVar4[(int)&DAT_10005718 - (int)unaff_EDI] == *pcVar4);
        if ((5 < (int)DVar3) && (DVar3 == DVar2)) {
          DAT_10005924 = *(undefined1 *)((int)unaff_EDI + 0x4c);
          DAT_10005920 = *(int *)((int)unaff_EDI + 0x58);
          iVar5 = 0;
          if (0 < DAT_10005920) {
            do {
              (&DAT_1000581d)[iVar5] = *(undefined1 *)((int)unaff_EDI + iVar5 + 0x44);
              iVar5 = iVar5 + 1;
            } while (iVar5 < DAT_10005920);
          }
          _DAT_1000555c = param_1;
          if (param_2 == '\0') {
            DAT_10005930 = param_5;
            DAT_10005928 = param_3;
            DAT_1000592c = param_4;
            return CONCAT31((int3)(param_5 >> 8),1);
          }
        }
      }
      in_EAX = pcVar1 + 1;
      pcVar1 = in_EAX;
      if (2 < (int)in_EAX) {
        return (uint)in_EAX & 0xffffff00;
      }
    }
  }
  return (uint)in_EAX & 0xffffff00;
}



// ===================== FUN_10001920 @ 10001920 =====================

/* WARNING: Removing unreachable block (ram,0x10001af7) */
/* WARNING: Removing unreachable block (ram,0x10001c6a) */
/* WARNING: Removing unreachable block (ram,0x10001c6f) */
/* WARNING: Removing unreachable block (ram,0x10001bd9) */
/* WARNING: Removing unreachable block (ram,0x10001c1c) */
/* WARNING: Removing unreachable block (ram,0x10001c45) */
/* WARNING: Removing unreachable block (ram,0x10001c54) */
/* WARNING: Removing unreachable block (ram,0x10001c60) */
/* WARNING: Removing unreachable block (ram,0x10001c76) */
/* WARNING: Removing unreachable block (ram,0x10001c7b) */
/* WARNING: Removing unreachable block (ram,0x10001c7f) */
/* WARNING: Removing unreachable block (ram,0x10001c96) */
/* WARNING: Removing unreachable block (ram,0x10001ca0) */
/* WARNING: Removing unreachable block (ram,0x10001cb5) */
/* WARNING: Removing unreachable block (ram,0x10001d0d) */
/* WARNING: Removing unreachable block (ram,0x10001a05) */
/* WARNING: Removing unreachable block (ram,0x10001a13) */
/* WARNING: Removing unreachable block (ram,0x10001a21) */
/* WARNING: Removing unreachable block (ram,0x10001a73) */
/* WARNING: Removing unreachable block (ram,0x10001a8b) */
/* WARNING: Removing unreachable block (ram,0x10001a90) */
/* WARNING: Removing unreachable block (ram,0x10001aa7) */
/* WARNING: Removing unreachable block (ram,0x10001b46) */
/* WARNING: Removing unreachable block (ram,0x10001b4c) */
/* WARNING: Removing unreachable block (ram,0x10001a2c) */
/* WARNING: Removing unreachable block (ram,0x100019a5) */
/* WARNING: Removing unreachable block (ram,0x100019e3) */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

uint FUN_10001920(uint param_1,uint param_2)

{
  undefined4 uVar1;
  uint uVar2;
  int iVar3;
  int unaff_EDI;
  int iStack_8;
  
  uVar2 = HidUart_GetNumDevices(&DAT_10005970,0,0);
  if ((uVar2 == 0) && (uVar2 = DAT_10005970, DAT_10005970 != 0)) {
    _DAT_10005974 = 0;
    if (iStack_8 == 4) {
      if (DAT_10005970 != 0) {
        do {
          iVar3 = 0;
          do {
            uVar1 = *(undefined4 *)(unaff_EDI + 0x50);
            if (DAT_10005978 != 0) {
              HidUart_IsOpened(DAT_10005978);
            }
            FUN_10002dd0(unaff_EDI,uVar1);
            Sleep(100);
            if (DAT_10005978 != 0) {
              HidUart_IsOpened(DAT_10005978);
            }
            iVar3 = iVar3 + 1;
          } while (iVar3 < 3);
          if (DAT_10005978 != 0) {
            HidUart_Close();
          }
          DAT_10005978 = 0;
          uVar2 = 0;
        } while (DAT_10005970 != _DAT_10005974);
      }
    }
    else if (DAT_10005970 != 0) {
      do {
        iVar3 = 0;
        do {
          uVar1 = *(undefined4 *)(unaff_EDI + 0x50);
          if (DAT_10005978 != 0) {
            HidUart_IsOpened(DAT_10005978);
          }
          FUN_10002dd0(unaff_EDI,uVar1);
          Sleep(100);
          if (DAT_10005978 != 0) {
            HidUart_IsOpened(DAT_10005978);
          }
          iVar3 = iVar3 + 1;
        } while (iVar3 < 3);
        if (DAT_10005978 != 0) {
          HidUart_Close();
        }
        DAT_10005978 = 0;
      } while (DAT_10005970 != _DAT_10005974);
      return 0;
    }
  }
  return uVar2 & 0xffffff00;
}



// ===================== FUN_10001d40 @ 10001d40 =====================

void FUN_10001d40(void)

{
  char cVar1;
  undefined2 *puVar2;
  undefined4 *puVar3;
  undefined1 *puVar4;
  uint uVar6;
  int iVar7;
  undefined2 local_dc;
  undefined1 local_da;
  undefined2 local_d8;
  undefined1 local_d6;
  undefined1 local_d4;
  undefined1 local_d3;
  undefined1 local_d2;
  undefined2 local_d0;
  undefined1 local_ce;
  undefined1 local_cc;
  undefined1 local_cb;
  undefined1 local_ca;
  undefined2 local_c8;
  undefined2 local_c4;
  undefined2 local_c0;
  undefined1 local_be;
  undefined1 local_bd;
  undefined4 local_bc;
  undefined4 local_b8;
  undefined4 local_b4;
  undefined1 local_b0 [52];
  undefined4 local_7c;
  undefined4 local_78;
  undefined1 local_74;
  undefined4 local_70;
  undefined4 local_6c;
  undefined4 local_68;
  undefined4 local_5c;
  undefined4 local_58;
  undefined4 local_54;
  undefined4 local_50;
  undefined4 local_4c;
  undefined4 local_48;
  undefined4 local_44;
  undefined4 local_40;
  undefined4 local_3c;
  undefined4 local_38;
  undefined4 local_34;
  undefined4 local_30;
  undefined4 local_2c;
  undefined4 local_28;
  undefined4 local_24;
  undefined4 local_20;
  undefined4 local_1c;
  undefined4 local_18;
  undefined4 local_14;
  undefined4 local_10;
  uint local_c;
  undefined4 uVar5;
  
  local_c = DAT_10005000 ^ (uint)&local_dc;
  iVar7 = 4;
  puVar2 = &local_c0;
  do {
    *(undefined1 *)puVar2 = 0;
    puVar2 = (undefined2 *)((int)puVar2 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  iVar7 = 0x40;
  puVar3 = &local_bc;
  do {
    *(undefined1 *)puVar3 = 0;
    puVar3 = (undefined4 *)((int)puVar3 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  iVar7 = 8;
  puVar3 = &local_7c;
  do {
    *(undefined1 *)puVar3 = 0;
    puVar3 = (undefined4 *)((int)puVar3 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  local_d8 = 0xffff;
  local_d6 = 0xa1;
  local_dc = 0xffff;
  local_da = 0xa2;
  local_d0 = 0xffff;
  local_ce = 0xa3;
  iVar7 = 4;
  puVar2 = &local_c0;
  do {
    *(undefined1 *)puVar2 = 0;
    puVar2 = (undefined2 *)((int)puVar2 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  iVar7 = 0x40;
  puVar3 = &local_bc;
  do {
    *(undefined1 *)puVar3 = 0;
    puVar3 = (undefined4 *)((int)puVar3 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  iVar7 = 8;
  puVar3 = &local_7c;
  do {
    *(undefined1 *)puVar3 = 0;
    puVar3 = (undefined4 *)((int)puVar3 + 1);
    iVar7 = iVar7 + -1;
  } while (iVar7 != 0);
  local_74 = 0;
  local_68 = 3;
  local_7c = CONCAT13(local_7c._3_1_,0xa3ffff);
  puVar4 = (undefined1 *)((int)&local_7c + 3);
  iVar7 = 5;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  local_6c = 3;
  local_bc = CONCAT13(local_bc._3_1_,0xa2ffff);
  puVar4 = (undefined1 *)((int)&local_bc + 3);
  iVar7 = 0x3d;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  local_c0 = 0xffff;
  local_be = 0xa1;
  local_70 = 3;
  puVar4 = &local_bd;
  iVar7 = 1;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  if (DAT_10005558 == 2) {
    uVar5 = FUN_10001920(4,1);
    cVar1 = (char)uVar5;
LAB_10001eec:
    if (cVar1 != '\0') goto LAB_10001ef0;
  }
  else if (DAT_10005558 == 1) {
    uVar5 = FUN_10001660(4,'\x01',0,0,0);
    cVar1 = (char)uVar5;
    goto LAB_10001eec;
  }
  local_c8 = 0xffff;
  local_34 = 0x6b696573;
  local_30 = 0x76203361;
  local_2c = 0x30302e32;
  local_5c = 0x6573ffff;
  local_58 = 0x616b69;
  FUN_10001210((undefined1 *)&local_c0);
  local_78 = local_58;
  local_7c = local_5c;
  local_bc = local_34;
  local_b4 = local_2c;
  local_74 = 1;
  local_68 = 8;
  local_6c = 0xc;
  local_b8 = local_30;
  puVar4 = local_b0;
  iVar7 = 0x34;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  local_c0 = local_c8;
  local_be = 0x1c;
  local_70 = 3;
  puVar4 = &local_bd;
  iVar7 = 1;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  if (DAT_10005558 == 2) {
    uVar5 = FUN_10001920(5,0);
    cVar1 = (char)uVar5;
LAB_10002081:
    if (cVar1 != '\0') goto LAB_10001ef0;
  }
  else if (DAT_10005558 == 1) {
    uVar5 = FUN_10001660(5,'\0',8,0x28,0x28);
    cVar1 = (char)uVar5;
    goto LAB_10002081;
  }
  local_c4 = 0xffff;
  local_54 = 0x6b696573;
  local_50 = 0x76303861;
  local_4c = 0x30302e31;
  local_14 = 0x3873ffff;
  local_10 = 0x30;
  FUN_10001210((undefined1 *)&local_c0);
  local_78 = local_10;
  local_7c = local_14;
  local_bc = local_54;
  local_b4 = local_4c;
  local_74 = 0;
  local_68 = 8;
  local_6c = 0xc;
  local_b8 = local_50;
  puVar4 = local_b0;
  iVar7 = 0x34;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  local_c0 = local_c4;
  local_be = 0x1c;
  local_70 = 3;
  puVar4 = &local_bd;
  iVar7 = 1;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  if (DAT_10005558 == 2) {
    uVar5 = FUN_10001920(6,0);
    cVar1 = (char)uVar5;
LAB_100021ff:
    if (cVar1 != '\0') goto LAB_10001ef0;
  }
  else if (DAT_10005558 == 1) {
    uVar5 = FUN_10001660(6,'\0',8,0x50,0x50);
    cVar1 = (char)uVar5;
    goto LAB_100021ff;
  }
  local_d4 = 0xff;
  local_d3 = 0xff;
  local_d2 = 10;
  local_40 = 0x8280500;
  local_3c = 0x6b696573;
  local_38 = 0x61;
  local_48 = 0x6573ffff;
  local_44 = 0x616b69;
  FUN_10001210((undefined1 *)&local_c0);
  local_78 = local_44;
  local_7c = local_48;
  local_bc = local_40;
  local_b4 = local_38;
  local_74 = 1;
  local_68 = 8;
  local_6c = 0xc;
  local_b8 = local_3c;
  puVar4 = local_b0;
  iVar7 = 0x34;
  do {
    *puVar4 = 0;
    iVar7 = iVar7 + -1;
    puVar4 = puVar4 + 1;
  } while (0 < iVar7);
  FUN_10001260((undefined2 *)&local_d4);
  uVar6 = FUN_10001600(0x28,8,&local_c0,7,0);
  if ((char)uVar6 == '\0') {
    local_cc = 0xff;
    local_cb = 0xff;
    local_ca = 10;
    local_28 = 0x8280500;
    local_24 = 0x302e3576;
    local_20 = 0x1010101;
    local_1c = 0x4ffff;
    local_18 = 0x500063;
    FUN_10001210((undefined1 *)&local_c0);
    local_78 = local_18;
    local_7c = local_1c;
    local_bc = local_28;
    local_b4 = local_20;
    local_74 = 1;
    local_68 = 8;
    local_6c = 0xc;
    local_b8 = local_24;
    puVar4 = local_b0;
    iVar7 = 0x34;
    do {
      *puVar4 = 0;
      iVar7 = iVar7 + -1;
      puVar4 = puVar4 + 1;
    } while (0 < iVar7);
    FUN_10001260((undefined2 *)&local_cc);
    FUN_10001600(0x28,8,&local_c0,8,0);
    __security_check_cookie(local_c ^ (uint)&local_dc);
    return;
  }
LAB_10001ef0:
  __security_check_cookie(local_c ^ (uint)&local_dc);
  return;
}



// ===================== FUN_100024a0 @ 100024a0 =====================

uint FUN_100024a0(void)

{
  undefined1 *puVar1;
  DWORD DVar2;
  uint uVar3;
  int iVar4;
  undefined4 extraout_ECX;
  undefined4 uVar5;
  undefined4 extraout_ECX_00;
  int unaff_EDI;
  
  iVar4 = 0x101;
  puVar1 = (undefined1 *)(unaff_EDI + 0x24c);
  do {
    *puVar1 = 0;
    puVar1 = puVar1 + 1;
    iVar4 = iVar4 + -1;
  } while (iVar4 != 0);
  iVar4 = 300;
  puVar1 = (undefined1 *)(unaff_EDI + 0xa4);
  do {
    *puVar1 = 0;
    puVar1 = puVar1 + 1;
    iVar4 = iVar4 + -1;
  } while (iVar4 != 0);
  uVar3 = *(uint *)(unaff_EDI + 0x88);
  *(undefined4 *)(unaff_EDI + 0x94) = 0;
  *(undefined4 *)(unaff_EDI + 0x98) = 0;
  if (uVar3 != 1) {
    if (uVar3 != 2) goto LAB_100025cb;
    DVar2 = FUN_10002ed0();
    uVar3 = 0;
    if (DVar2 == 0) goto LAB_1000251e;
    uVar5 = extraout_ECX_00;
    if (*(int *)(unaff_EDI + 0x8c) == 4) goto LAB_1000250d;
    if (DVar2 == 2) {
      FUN_100025e0();
    }
    uVar3 = *(uint *)(unaff_EDI + 0x8c);
    if (uVar3 == 6) {
LAB_100025a8:
      if (0x15 < DVar2) {
LAB_100025ad:
        uVar3 = FUN_10002640();
      }
    }
    else {
      if (0xb < DVar2) goto LAB_100025ad;
      if (uVar3 == 6) goto LAB_100025a8;
    }
    if ((*(int *)(unaff_EDI + 0x94) == 0) && (*(int *)(unaff_EDI + 0x98) == 0)) {
      return uVar3 & 0xffffff00;
    }
    goto LAB_100025cb;
  }
  DVar2 = FUN_10002cb0((undefined1 *)(unaff_EDI + 0x24c));
  uVar3 = DVar2;
  if ((int)DVar2 < 1) goto LAB_1000251e;
  uVar5 = extraout_ECX;
  if (*(int *)(unaff_EDI + 0x8c) == 4) {
LAB_1000250d:
    uVar3 = FUN_10002760(uVar5,DVar2);
    if ((char)uVar3 == '\0') {
LAB_1000251e:
      return uVar3 & 0xffffff00;
    }
    goto LAB_100025cb;
  }
  if (DVar2 == 2) {
    FUN_100025e0();
  }
  uVar3 = *(uint *)(unaff_EDI + 0x8c);
  if (uVar3 == 6) {
LAB_10002544:
    if (0x15 < (int)DVar2) {
LAB_10002549:
      uVar3 = FUN_10002640();
    }
  }
  else {
    if (0xb < (int)DVar2) goto LAB_10002549;
    if (uVar3 == 6) goto LAB_10002544;
  }
  if ((*(int *)(unaff_EDI + 0x94) == 0) && (*(int *)(unaff_EDI + 0x98) == 0)) {
    return uVar3 & 0xffffff00;
  }
LAB_100025cb:
  if (*(char *)(unaff_EDI + 0x4ac) != '\0') {
    uVar3 = FUN_100026a0(unaff_EDI);
  }
  return CONCAT31((int3)(uVar3 >> 8),1);
}



// ===================== FUN_100025e0 @ 100025e0 =====================

bool FUN_100025e0(void)

{
  byte bVar1;
  byte bVar2;
  uint uVar3;
  int unaff_ESI;
  
  bVar1 = *(byte *)(unaff_ESI + 0x24c);
  uVar3 = (uint)((bVar1 & 1) != 0);
  bVar2 = *(byte *)(unaff_ESI + 0x24d);
  if ((bVar2 & 2) != 0) {
    uVar3 = uVar3 | 2;
  }
  if ((bVar2 & 8) != 0) {
    uVar3 = uVar3 | 4;
  }
  if ((bVar2 & 0x10) != 0) {
    uVar3 = uVar3 | 8;
  }
  if ((bVar1 & 0x10) != 0) {
    uVar3 = uVar3 | 0x10;
  }
  if ((bVar1 & 8) != 0) {
    uVar3 = uVar3 | 0x20;
  }
  if ((bVar1 & 2) != 0) {
    uVar3 = uVar3 | 0x40;
  }
  if ((bVar1 & 4) != 0) {
    uVar3 = uVar3 | 0x80;
  }
  *(uint *)(unaff_ESI + 0x94) = uVar3;
  return uVar3 != 0;
}



// ===================== FUN_10002640 @ 10002640 =====================

uint FUN_10002640(void)

{
  char cVar1;
  byte bVar2;
  uint in_EAX;
  uint uVar3;
  int unaff_ESI;
  
  if (*(char *)(unaff_ESI + 0x24d) == '\b') {
    cVar1 = *(char *)(unaff_ESI + 0x24e);
    in_EAX = CONCAT31((int3)(in_EAX >> 8),cVar1);
    if (((cVar1 == '\t') || (cVar1 == '\x0f')) && (in_EAX = 0, *(int *)(unaff_ESI + 0x45c) != 0)) {
      do {
        bVar2 = (byte)in_EAX;
        uVar3 = in_EAX >> 3;
        in_EAX = in_EAX + 1;
        if ((*(byte *)(uVar3 + 0x253 + unaff_ESI) & (byte)(1 << (bVar2 & 7))) != 0) {
          *(uint *)(unaff_ESI + 0x98) = in_EAX;
          return CONCAT31((int3)(in_EAX >> 8),1);
        }
      } while (in_EAX < *(uint *)(unaff_ESI + 0x45c));
    }
  }
  return in_EAX & 0xffffff00;
}



// ===================== FUN_100026a0 @ 100026a0 =====================

void FUN_100026a0(int param_1)

{
  char *_Dst;
  bool bVar1;
  int iVar2;
  
  _Dst = (char *)(param_1 + 0xa4);
  memset(_Dst,0,300);
  if (*(int *)(param_1 + 0x94) == 0) {
    strcat_s(_Dst,300,"Routing");
    return;
  }
  bVar1 = false;
  iVar2 = 0;
  do {
    if ((*(uint *)(param_1 + 0x94) & 1 << ((byte)iVar2 & 0x1f)) != 0) {
      if (bVar1) {
        strcat_s(_Dst,300,"+");
      }
      bVar1 = true;
      strcat_s(_Dst,300,(&PTR_DAT_10005010)[iVar2]);
    }
    iVar2 = iVar2 + 1;
  } while (iVar2 < 0x1e);
  if ((*(int *)(param_1 + 0x98) != 0) && (bVar1)) {
    strcat_s(_Dst,300,"+Routing");
  }
  return;
}



// ===================== FUN_10002760 @ 10002760 =====================

undefined4 __fastcall FUN_10002760(undefined4 param_1,uint param_2)

{
  char cVar1;
  byte bVar2;
  int in_EAX;
  uint3 uVar3;
  uint uVar4;
  byte bVar5;
  uint uVar6;
  uint uVar7;
  
  uVar3 = (uint3)((uint)in_EAX >> 8);
  if (((4 < param_2) && (*(char *)(in_EAX + 0x24c) == -1)) && (*(char *)(in_EAX + 0x24d) == -1)) {
    cVar1 = *(char *)(in_EAX + 0x24e);
    if (cVar1 == -0x5c) {
      if ((((uint)*(byte *)(in_EAX + 0x24f) == *(uint *)(in_EAX + 0xa0)) &&
          (param_2 == *(uint *)(in_EAX + 0xa0) + 4)) && (uVar7 = 0, *(int *)(in_EAX + 0x460) != 0))
      {
        do {
          bVar2 = (byte)uVar7;
          uVar4 = uVar7 >> 3;
          uVar7 = uVar7 + 1;
          if ((*(byte *)(uVar4 + 0x250 + in_EAX) & (byte)(1 << (bVar2 & 7))) != 0) {
            *(uint *)(in_EAX + 0x98) = uVar7;
            return CONCAT31(uVar3,1);
          }
        } while (uVar7 < *(uint *)(in_EAX + 0x460));
      }
    }
    else if (cVar1 == -0x5a) {
      if (((uint)*(byte *)(in_EAX + 0x24f) == *(uint *)(in_EAX + 0x9c)) &&
         (param_2 == *(uint *)(in_EAX + 0x9c) + 4)) {
        uVar7 = 0;
        if (*(int *)(in_EAX + 0x458) != 0) {
          do {
            if ((*(byte *)((uVar7 >> 3) + 0x250 + in_EAX) & (byte)(1 << ((byte)uVar7 & 7))) != 0) {
              *(uint *)(in_EAX + 0x94) = *(uint *)(in_EAX + 0x94) | 1 << ((byte)uVar7 + 8 & 0x1f);
            }
            uVar7 = uVar7 + 1;
          } while (uVar7 < *(uint *)(in_EAX + 0x458));
        }
        if (*(int *)(in_EAX + 0x94) != 0) {
          return CONCAT31(uVar3,1);
        }
      }
    }
    else if (cVar1 == -0x58) {
      uVar7 = *(uint *)(in_EAX + 0x9c);
      uVar4 = *(int *)(in_EAX + 0xa0) + uVar7;
      if ((*(byte *)(in_EAX + 0x24f) == uVar4) && (param_2 == uVar4 + 4)) {
        uVar6 = 0;
        if (uVar4 * 8 != 0) {
          do {
            bVar2 = *(byte *)((uVar6 >> 3) + 0x250 + in_EAX);
            bVar5 = (byte)(1 << ((byte)uVar6 & 7));
            if (uVar6 >> 3 < uVar7) {
              if ((bVar2 & bVar5) != 0) {
                *(uint *)(in_EAX + 0x94) = *(uint *)(in_EAX + 0x94) | 1 << ((byte)uVar6 + 8 & 0x1f);
              }
            }
            else if ((bVar2 & bVar5) != 0) {
              *(uint *)(in_EAX + 0x98) = uVar6 + uVar7 * -8 + 1;
              break;
            }
            uVar6 = uVar6 + 1;
          } while (uVar6 < uVar4 * 8);
        }
        if ((*(int *)(in_EAX + 0x94) != 0) || (0 < *(int *)(in_EAX + 0x98))) {
          return CONCAT31(uVar3,1);
        }
      }
    }
  }
  return (uint)uVar3 << 8;
}



// ===================== FUN_10002930 @ 10002930 =====================

bool FUN_10002930(void)

{
  DAT_10005710 = _beginthreadex((void *)0x0,0,(_StartAddress *)&LAB_10002960,&DAT_100054d0,0,
                                &DAT_10005714);
  return DAT_10005710 != 0;
}



// ===================== FUN_10002970 @ 10002970 =====================

undefined4 FUN_10002970(void)

{
  int in_EAX;
  UINT_PTR uIDEvent;
  int iVar1;
  undefined4 uVar2;
  tagMSG local_1c;
  
  uIDEvent = SetTimer((HWND)0x0,0,0x32,(TIMERPROC)0x0);
  do {
    iVar1 = GetMessageA(&local_1c,(HWND)0x0,0,0);
    while (0 < iVar1) {
      if (local_1c.message == 0x113) {
        uVar2 = FUN_100024a0();
        if ((char)uVar2 != '\0') {
          PostMessageA(*(HWND *)(in_EAX + 0x4b0),*(UINT *)(in_EAX + 0x4b4),
                       *(WPARAM *)(in_EAX + 0x94),*(LPARAM *)(in_EAX + 0x98));
        }
      }
      else {
        DispatchMessageA(&local_1c);
      }
      iVar1 = GetMessageA(&local_1c,(HWND)0x0,0,0);
    }
  } while ((iVar1 == -1) || (iVar1 != 0));
  if (uIDEvent != 0) {
    KillTimer((HWND)0x0,uIDEvent);
  }
  SetEvent(*(HANDLE *)(in_EAX + 0x248));
  return 0;
}



// ===================== FUN_10002a30 @ 10002a30 =====================

void FUN_10002a30(int param_1,DWORD param_2)

{
  HANDLE pvVar1;
  BOOL BVar2;
  undefined4 *unaff_ESI;
  char *pcVar3;
  _COMMTIMEOUTS local_44;
  _DCB local_30;
  CHAR local_14 [16];
  uint local_4;
  
  local_4 = DAT_10005000 ^ (uint)&local_44;
  if (*(char *)(unaff_ESI + 0xb) != '\0') {
    __security_check_cookie(local_4 ^ (uint)&local_44);
    return;
  }
  if (param_1 < 10) {
    pcVar3 = "COM%d";
  }
  else {
    pcVar3 = "\\\\.\\COM%d";
  }
  wsprintfA(local_14,pcVar3,param_1);
  pvVar1 = CreateFileA(local_14,0xc0000000,0,(LPSECURITY_ATTRIBUTES)0x0,3,0x40000080,(HANDLE)0x0);
  *unaff_ESI = pvVar1;
  if (pvVar1 == (HANDLE)0x0) {
    *(undefined1 *)(unaff_ESI + 0xb) = 0;
    __security_check_cookie(local_4 ^ (uint)&local_44);
    return;
  }
  local_44.ReadTotalTimeoutConstant = 0x32;
  local_44.WriteTotalTimeoutConstant = 0x32;
  local_44.ReadIntervalTimeout = 0;
  local_44.ReadTotalTimeoutMultiplier = 5;
  local_44.WriteTotalTimeoutMultiplier = 5;
  SetCommTimeouts(pvVar1,&local_44);
  pvVar1 = CreateEventA((LPSECURITY_ATTRIBUTES)0x0,1,0,(LPCSTR)0x0);
  unaff_ESI[5] = pvVar1;
  pvVar1 = CreateEventA((LPSECURITY_ATTRIBUTES)0x0,1,0,(LPCSTR)0x0);
  unaff_ESI[10] = pvVar1;
  ResetEvent((HANDLE)unaff_ESI[5]);
  ResetEvent((HANDLE)unaff_ESI[10]);
  local_30.DCBlength = 0x1c;
  GetCommState((HANDLE)*unaff_ESI,&local_30);
  local_30.BaudRate = param_2;
  local_30.ByteSize = '\b';
  BVar2 = SetCommState((HANDLE)*unaff_ESI,&local_30);
  if (BVar2 != 0) {
    BVar2 = SetupComm((HANDLE)*unaff_ESI,10000,10000);
    if (((BVar2 != 0) && (unaff_ESI[5] != 0)) && (unaff_ESI[10] != 0)) {
      *(undefined1 *)(unaff_ESI + 0xb) = 1;
      __security_check_cookie(local_4 ^ (uint)&local_44);
      return;
    }
  }
  if ((HANDLE)unaff_ESI[5] != (HANDLE)0x0) {
    CloseHandle((HANDLE)unaff_ESI[5]);
  }
  if ((HANDLE)unaff_ESI[10] != (HANDLE)0x0) {
    CloseHandle((HANDLE)unaff_ESI[10]);
  }
  CloseHandle((HANDLE)*unaff_ESI);
  *unaff_ESI = 0;
  *(undefined1 *)(unaff_ESI + 0xb) = 0;
  __security_check_cookie(local_4 ^ (uint)&local_44);
  return;
}



// ===================== FUN_10002bd0 @ 10002bd0 =====================

undefined4 FUN_10002bd0(void)

{
  BOOL in_EAX;
  int *unaff_ESI;
  
  if (((char)unaff_ESI[0xb] != '\0') || (*unaff_ESI != 0)) {
    if ((HANDLE)unaff_ESI[5] != (HANDLE)0x0) {
      CloseHandle((HANDLE)unaff_ESI[5]);
    }
    if ((HANDLE)unaff_ESI[10] != (HANDLE)0x0) {
      CloseHandle((HANDLE)unaff_ESI[10]);
    }
    in_EAX = 0;
    if ((HANDLE)*unaff_ESI != (HANDLE)0x0) {
      in_EAX = CloseHandle((HANDLE)*unaff_ESI);
    }
    *(undefined1 *)(unaff_ESI + 0xb) = 0;
    *unaff_ESI = 0;
  }
  return CONCAT31((int3)((uint)in_EAX >> 8),1);
}



// ===================== FUN_10002c10 @ 10002c10 =====================

DWORD FUN_10002c10(LPCVOID param_1,DWORD param_2)

{
  BOOL BVar1;
  DWORD DVar2;
  undefined4 *unaff_ESI;
  DWORD local_14;
  DWORD local_10;
  _COMSTAT local_c;
  
  if ((*(char *)(unaff_ESI + 0xb) != '\0') && ((HANDLE)*unaff_ESI != (HANDLE)0x0)) {
    local_14 = 0;
    ClearCommError((HANDLE)*unaff_ESI,&local_10,&local_c);
    BVar1 = WriteFile((HANDLE)*unaff_ESI,param_1,param_2,&local_14,(LPOVERLAPPED)(unaff_ESI + 6));
    if (BVar1 == 0) {
      DVar2 = GetLastError();
      if (DVar2 == 0x3e5) {
        WaitForSingleObject((HANDLE)unaff_ESI[10],200);
        GetOverlappedResult((HANDLE)*unaff_ESI,(LPOVERLAPPED)(unaff_ESI + 6),&local_14,0);
        return local_14;
      }
      *(undefined1 *)(unaff_ESI + 0xb) = 0;
    }
    return local_14;
  }
  return 0;
}



// ===================== FUN_10002cb0 @ 10002cb0 =====================

DWORD FUN_10002cb0(LPVOID param_1)

{
  BOOL BVar1;
  DWORD DVar2;
  undefined4 *unaff_ESI;
  DWORD local_14;
  DWORD local_10;
  _COMSTAT local_c;
  
  if ((*(char *)(unaff_ESI + 0xb) != '\0') && ((HANDLE)*unaff_ESI != (HANDLE)0x0)) {
    local_14 = 0;
    ClearCommError((HANDLE)*unaff_ESI,&local_10,&local_c);
    if (local_c.cbInQue != 0) {
      if (0x101 < (int)local_c.cbInQue) {
        local_c.cbInQue = 0x101;
      }
      BVar1 = ReadFile((HANDLE)*unaff_ESI,param_1,local_c.cbInQue,&local_14,
                       (LPOVERLAPPED)(unaff_ESI + 1));
      if (BVar1 == 0) {
        DVar2 = GetLastError();
        if (DVar2 == 0x3e5) {
          WaitForSingleObject((HANDLE)unaff_ESI[5],0x32);
          GetOverlappedResult((HANDLE)*unaff_ESI,(LPOVERLAPPED)(unaff_ESI + 1),&local_14,0);
          return local_14;
        }
        *(undefined1 *)(unaff_ESI + 0xb) = 0;
      }
      return local_14;
    }
  }
  return 0;
}



// ===================== FUN_10002d60 @ 10002d60 =====================

undefined4 __fastcall FUN_10002d60(int param_1)

{
  undefined4 in_EAX;
  undefined4 unaff_EDI;
  int local_4;
  
  local_4 = 0;
  if (*(int *)(param_1 + 0x10) != 0) {
    HidUart_IsOpened(*(int *)(param_1 + 0x10),&local_4);
    if (local_4 != 0) {
      HidUart_Write(*(undefined4 *)(param_1 + 0x10));
      return unaff_EDI;
    }
  }
  FUN_10002dd0(in_EAX,unaff_EDI);
  return 0;
}



// ===================== FUN_10002dd0 @ 10002dd0 =====================

void FUN_10002dd0(undefined4 param_1,undefined4 param_2)

{
  int *piVar1;
  int iVar2;
  undefined4 *unaff_ESI;
  undefined1 local_114 [272];
  uint local_4;
  
  local_4 = DAT_10005000 ^ (uint)local_114;
  if ((uint)unaff_ESI[3] < (uint)unaff_ESI[2]) {
    do {
      iVar2 = HidUart_GetString(unaff_ESI[3],0,0,local_114,4);
      if (iVar2 == 0) {
        piVar1 = unaff_ESI + 4;
        iVar2 = HidUart_Open(piVar1,unaff_ESI[3],0,0);
        if (iVar2 == 0) {
          iVar2 = HidUart_SetUartConfig
                            (*piVar1,*unaff_ESI,*(undefined1 *)(unaff_ESI + 1),
                             *(undefined1 *)((int)unaff_ESI + 5),*(undefined1 *)((int)unaff_ESI + 6)
                             ,*(undefined1 *)((int)unaff_ESI + 7));
          if (((iVar2 == 0) && (iVar2 = HidUart_SetTimeouts(*piVar1,0x14,500), iVar2 == 0)) &&
             (iVar2 = HidUart_Write(*piVar1,param_1,param_2), iVar2 == 0)) {
            unaff_ESI[3] = unaff_ESI[3] + 1;
            break;
          }
          if (*piVar1 != 0) {
            HidUart_Close(*piVar1);
          }
          *piVar1 = 0;
        }
      }
      unaff_ESI[3] = unaff_ESI[3] + 1;
    } while ((uint)unaff_ESI[3] < (uint)unaff_ESI[2]);
  }
  __security_check_cookie(local_4 ^ (uint)local_114);
  return;
}



// ===================== FUN_10002ed0 @ 10002ed0 =====================

int FUN_10002ed0(void)

{
  int unaff_ESI;
  int iStack_10;
  undefined4 *puStack_c;
  undefined4 local_8;
  undefined4 local_4;
  
  iStack_10 = 0;
  local_8 = 0;
  local_4 = 0;
  if (*(int *)(unaff_ESI + 0x10) != 0) {
    puStack_c = &local_4;
    iStack_10 = *(int *)(unaff_ESI + 0x10);
    HidUart_IsOpened();
    if (puStack_c != (undefined4 *)0x0) {
      HidUart_Read(*(undefined4 *)(unaff_ESI + 0x10),local_4,0x101,&iStack_10);
    }
  }
  return iStack_10;
}



// ===================== __security_check_cookie @ 10002f17 =====================

/* Library Function - Single Match
    @__security_check_cookie@4
   
   Libraries: Visual Studio 2005 Release, Visual Studio 2008 Release, Visual Studio 2010 Release
   __fastcall __security_check_cookie,4 */

void __fastcall __security_check_cookie(int param_1)

{
  if (param_1 == DAT_10005000) {
    return;
  }
                    /* WARNING: Subroutine does not return */
  ___report_gsfailure();
}



// ===================== __CRT_INIT@12 @ 10003024 =====================

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */
/* Library Function - Single Match
    __CRT_INIT@12
   
   Library: Visual Studio 2005 Release */

undefined4 __CRT_INIT_12(undefined4 param_1,int param_2,undefined4 param_3)

{
  LONG LVar1;
  int iVar2;
  BOOL BVar3;
  undefined4 *_Memory;
  undefined4 *puVar4;
  bool bVar5;
  
  bVar5 = false;
  if (param_2 == 0) {
    if (DAT_10005190 < 1) {
      return 0;
    }
    DAT_10005190 = DAT_10005190 + -1;
  }
  _DAT_10005988 = *(undefined4 *)_adjust_fdiv_exref;
  if (param_2 == 1) {
    iVar2 = *(int *)((int)Self + 4);
    while (LVar1 = InterlockedCompareExchange((LONG *)&DAT_10005990,iVar2,0), LVar1 != 0) {
      if (LVar1 == iVar2) {
        bVar5 = true;
        break;
      }
      Sleep(1000);
    }
    if (DAT_1000598c == 0) {
      DAT_1000598c = 1;
      iVar2 = initterm_e(&DAT_10004120,&DAT_10004128);
      if (iVar2 != 0) {
        return 0;
      }
      initterm(&DAT_10004114,&DAT_1000411c);
      DAT_1000598c = 2;
    }
    else {
      _amsg_exit(0x1f);
    }
    if (!bVar5) {
      InterlockedExchange((LONG *)&DAT_10005990,0);
    }
    if ((DAT_10005994 != (code *)0x0) &&
       (BVar3 = __IsNonwritableInCurrentImage((PBYTE)&DAT_10005994), BVar3 != 0)) {
      (*DAT_10005994)(param_1,2,param_3);
    }
    DAT_10005190 = DAT_10005190 + 1;
  }
  else if (param_2 == 0) {
    while (LVar1 = InterlockedCompareExchange((LONG *)&DAT_10005990,1,0), LVar1 != 0) {
      Sleep(1000);
    }
    if (DAT_1000598c == 2) {
      _Memory = (undefined4 *)decode_pointer(DAT_1000599c);
      if (_Memory != (undefined4 *)0x0) {
        puVar4 = (undefined4 *)decode_pointer(DAT_10005998);
        while (puVar4 = puVar4 + -1, _Memory <= puVar4) {
          if ((code *)*puVar4 != (code *)0x0) {
            (*(code *)*puVar4)();
          }
        }
        free(_Memory);
        DAT_10005998 = encoded_null();
        DAT_1000599c = DAT_10005998;
      }
      DAT_1000598c = 0;
      InterlockedExchange((LONG *)&DAT_10005990,0);
    }
    else {
      _amsg_exit(0x1f);
    }
  }
  return 1;
}



// ===================== ___DllMainCRTStartup @ 100031c3 =====================

/* WARNING: Function: __SEH_prolog4 replaced with injection: SEH_prolog4 */
/* WARNING: Function: __SEH_epilog4 replaced with injection: EH_epilog3 */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */
/* Library Function - Single Match
    ___DllMainCRTStartup
   
   Library: Visual Studio 2005 Release */

int __fastcall ___DllMainCRTStartup(undefined4 param_1,int param_2,undefined4 param_3)

{
  int iVar1;
  undefined4 local_20;
  
  local_20 = 1;
  _DAT_10005008 = param_2;
  if ((param_2 == 0) && (DAT_10005190 == 0)) {
    local_20 = 0;
    goto LAB_100032b5;
  }
  if ((param_2 == 1) || (param_2 == 2)) {
    if (DAT_1000414c != (code *)0x0) {
      local_20 = (*DAT_1000414c)(param_3,param_2,param_1);
    }
    if (local_20 == 0) goto LAB_100032b5;
    local_20 = __CRT_INIT_12(param_3,param_2,param_1);
    if (local_20 == 0) goto LAB_100032b5;
  }
  local_20 = FUN_10001000();
  if ((param_2 == 1) && (local_20 == 0)) {
    FUN_10001000();
    __CRT_INIT_12(param_3,0,param_1);
    if (DAT_1000414c != (code *)0x0) {
      (*DAT_1000414c)(param_3,0,param_1);
    }
  }
  if ((param_2 == 0) || (param_2 == 3)) {
    iVar1 = __CRT_INIT_12(param_3,param_2,param_1);
    if (iVar1 == 0) {
      local_20 = 0;
    }
    if ((local_20 != 0) && (DAT_1000414c != (code *)0x0)) {
      local_20 = (*DAT_1000414c)(param_3,param_2,param_1);
    }
  }
LAB_100032b5:
  FUN_100032ce();
  return local_20;
}



// ===================== FUN_100032ce @ 100032ce =====================

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_100032ce(void)

{
  _DAT_10005008 = 0xffffffff;
  return;
}



// ===================== entry @ 100032d9 =====================

void entry(undefined4 param_1,int param_2,undefined4 param_3)

{
  if (param_2 == 1) {
    ___security_init_cookie();
  }
  ___DllMainCRTStartup(param_3,param_2,param_1);
  return;
}



// ===================== ___report_gsfailure @ 100032fa =====================

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */
/* Library Function - Single Match
    ___report_gsfailure
   
   Libraries: Visual Studio 2005 Release, Visual Studio 2008 Release, Visual Studio 2010 Release */

void __cdecl ___report_gsfailure(void)

{
  undefined4 in_EAX;
  HANDLE hProcess;
  undefined4 in_ECX;
  undefined4 in_EDX;
  undefined4 unaff_EBX;
  undefined4 unaff_EBP;
  undefined4 unaff_ESI;
  undefined4 unaff_EDI;
  undefined2 in_ES;
  undefined2 in_CS;
  undefined2 in_SS;
  undefined2 in_DS;
  undefined2 in_FS;
  undefined2 in_GS;
  byte in_AF;
  byte in_TF;
  byte in_IF;
  byte in_NT;
  byte in_AC;
  byte in_VIF;
  byte in_VIP;
  byte in_ID;
  undefined4 unaff_retaddr;
  UINT uExitCode;
  undefined4 local_32c;
  undefined4 local_328;
  
  _DAT_100052b0 =
       (uint)(in_NT & 1) * 0x4000 | (uint)SBORROW4((int)&stack0xfffffffc,0x328) * 0x800 |
       (uint)(in_IF & 1) * 0x200 | (uint)(in_TF & 1) * 0x100 | (uint)((int)&local_32c < 0) * 0x80 |
       (uint)(&stack0x00000000 == (undefined1 *)0x32c) * 0x40 | (uint)(in_AF & 1) * 0x10 |
       (uint)((POPCOUNT((uint)&local_32c & 0xff) & 1U) == 0) * 4 |
       (uint)(&stack0xfffffffc < (undefined1 *)0x328) | (uint)(in_ID & 1) * 0x200000 |
       (uint)(in_VIP & 1) * 0x100000 | (uint)(in_VIF & 1) * 0x80000 | (uint)(in_AC & 1) * 0x40000;
  _DAT_100052b4 = &stack0x00000004;
  _DAT_100051f0 = 0x10001;
  _DAT_10005198 = 0xc0000409;
  _DAT_1000519c = 1;
  local_32c = DAT_10005000;
  local_328 = DAT_10005004;
  _DAT_100051a4 = unaff_retaddr;
  _DAT_1000527c = in_GS;
  _DAT_10005280 = in_FS;
  _DAT_10005284 = in_ES;
  _DAT_10005288 = in_DS;
  _DAT_1000528c = unaff_EDI;
  _DAT_10005290 = unaff_ESI;
  _DAT_10005294 = unaff_EBX;
  _DAT_10005298 = in_EDX;
  _DAT_1000529c = in_ECX;
  _DAT_100052a0 = in_EAX;
  _DAT_100052a4 = unaff_EBP;
  DAT_100052a8 = unaff_retaddr;
  _DAT_100052ac = in_CS;
  _DAT_100052b8 = in_SS;
  DAT_100051e8 = IsDebuggerPresent();
  _crt_debugger_hook(1);
  SetUnhandledExceptionFilter((LPTOP_LEVEL_EXCEPTION_FILTER)0x0);
  UnhandledExceptionFilter((_EXCEPTION_POINTERS *)&PTR_DAT_10004150);
  if (DAT_100051e8 == 0) {
    _crt_debugger_hook(1);
  }
  uExitCode = 0xc0000409;
  hProcess = GetCurrentProcess();
  TerminateProcess(hProcess,uExitCode);
  return;
}



// ===================== __SEH_prolog4 @ 10003410 =====================

/* WARNING: This is an inlined function */
/* WARNING: Unable to track spacebase fully for stack */
/* WARNING: Variable defined which should be unmapped: param_2 */
/* Library Function - Single Match
    __SEH_prolog4
   
   Library: Visual Studio */

void __cdecl __SEH_prolog4(undefined4 param_1,int param_2)

{
  int iVar1;
  undefined4 unaff_EBX;
  undefined4 unaff_ESI;
  undefined4 unaff_EDI;
  undefined4 unaff_retaddr;
  uint auStack_1c [5];
  undefined1 local_8 [8];
  
  iVar1 = -param_2;
  *(undefined4 *)((int)auStack_1c + iVar1 + 0x10) = unaff_EBX;
  *(undefined4 *)((int)auStack_1c + iVar1 + 0xc) = unaff_ESI;
  *(undefined4 *)((int)auStack_1c + iVar1 + 8) = unaff_EDI;
  *(uint *)((int)auStack_1c + iVar1 + 4) = DAT_10005000 ^ (uint)&param_2;
  *(undefined4 *)((int)auStack_1c + iVar1) = unaff_retaddr;
  ExceptionList = local_8;
  return;
}



// ===================== __SEH_epilog4 @ 10003455 =====================

/* WARNING: This is an inlined function */
/* Library Function - Single Match
    __SEH_epilog4
   
   Library: Visual Studio */

void __SEH_epilog4(void)

{
  undefined4 *unaff_EBP;
  undefined4 unaff_retaddr;
  
  ExceptionList = (void *)unaff_EBP[-4];
  *unaff_EBP = unaff_retaddr;
  return;
}



// ===================== __ValidateImageBase @ 100034e0 =====================

/* Library Function - Single Match
    __ValidateImageBase
   
   Library: Visual Studio 2005 Release */

BOOL __cdecl __ValidateImageBase(PBYTE pImageBase)

{
  if ((*(short *)pImageBase == 0x5a4d) &&
     (*(int *)(pImageBase + *(int *)(pImageBase + 0x3c)) == 0x4550)) {
    return (uint)((short)*(int *)((int)(pImageBase + *(int *)(pImageBase + 0x3c)) + 0x18) == 0x10b);
  }
  return 0;
}



// ===================== __FindPESection @ 10003510 =====================

/* Library Function - Single Match
    __FindPESection
   
   Library: Visual Studio 2005 Release */

PIMAGE_SECTION_HEADER __cdecl __FindPESection(PBYTE pImageBase,DWORD_PTR rva)

{
  int iVar1;
  PIMAGE_SECTION_HEADER p_Var2;
  uint uVar3;
  
  iVar1 = *(int *)(pImageBase + 0x3c);
  uVar3 = 0;
  p_Var2 = (PIMAGE_SECTION_HEADER)
           (pImageBase + *(ushort *)(pImageBase + iVar1 + 0x14) + 0x18 + iVar1);
  if (*(ushort *)(pImageBase + iVar1 + 6) != 0) {
    do {
      if ((p_Var2->VirtualAddress <= rva) &&
         (rva < (p_Var2->Misc).PhysicalAddress + p_Var2->VirtualAddress)) {
        return p_Var2;
      }
      uVar3 = uVar3 + 1;
      p_Var2 = p_Var2 + 1;
    } while (uVar3 < *(ushort *)(pImageBase + iVar1 + 6));
  }
  return (PIMAGE_SECTION_HEADER)0x0;
}



// ===================== __IsNonwritableInCurrentImage @ 10003552 =====================

/* WARNING: Function: __SEH_prolog4 replaced with injection: SEH_prolog4 */
/* WARNING: Function: __SEH_epilog4 replaced with injection: EH_epilog3 */
/* Library Function - Single Match
    __IsNonwritableInCurrentImage
   
   Library: Visual Studio 2005 Release */

BOOL __cdecl __IsNonwritableInCurrentImage(PBYTE pTarget)

{
  BOOL BVar1;
  PIMAGE_SECTION_HEADER p_Var2;
  PBYTE pImageBase;
  
  BVar1 = __ValidateImageBase((PBYTE)&IMAGE_DOS_HEADER_10000000);
  if ((BVar1 != 0) &&
     (p_Var2 = __FindPESection(pImageBase,(int)pTarget - (int)pImageBase),
     p_Var2 != (PIMAGE_SECTION_HEADER)0x0)) {
    return ~(p_Var2->Characteristics >> 0x1f) & 1;
  }
  return 0;
}



// ===================== initterm @ 100035be =====================

void __cdecl initterm(void)

{
                    /* WARNING: Could not recover jumptable at 0x100035be. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  initterm();
  return;
}



// ===================== initterm_e @ 100035c4 =====================

void __cdecl initterm_e(void)

{
                    /* WARNING: Could not recover jumptable at 0x100035c4. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  initterm_e();
  return;
}



// ===================== _amsg_exit @ 100035ca =====================

void __cdecl _amsg_exit(int param_1)

{
                    /* WARNING: Could not recover jumptable at 0x100035ca. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  _amsg_exit(param_1);
  return;
}



// ===================== __CppXcptFilter @ 100035d0 =====================

int __cdecl __CppXcptFilter(ulong _ExceptionNum,_EXCEPTION_POINTERS *_ExceptionPtr)

{
  int iVar1;
  
                    /* WARNING: Could not recover jumptable at 0x100035d0. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  iVar1 = __CppXcptFilter(_ExceptionNum,_ExceptionPtr);
  return iVar1;
}



// ===================== ___security_init_cookie @ 100035d6 =====================

/* Library Function - Single Match
    ___security_init_cookie
   
   Library: Visual Studio 2005 Release */

void __cdecl ___security_init_cookie(void)

{
  DWORD DVar1;
  DWORD DVar2;
  DWORD DVar3;
  uint uVar4;
  LARGE_INTEGER local_14;
  _FILETIME local_c;
  
  local_c.dwLowDateTime = 0;
  local_c.dwHighDateTime = 0;
  if ((DAT_10005000 == 0xbb40e64e) || ((DAT_10005000 & 0xffff0000) == 0)) {
    GetSystemTimeAsFileTime(&local_c);
    uVar4 = local_c.dwHighDateTime ^ local_c.dwLowDateTime;
    DVar1 = GetCurrentProcessId();
    DVar2 = GetCurrentThreadId();
    DVar3 = GetTickCount();
    QueryPerformanceCounter(&local_14);
    DAT_10005000 = uVar4 ^ DVar1 ^ DVar2 ^ DVar3 ^ local_14.s.HighPart ^ local_14.s.LowPart;
    if (DAT_10005000 == 0xbb40e64e) {
      DAT_10005000 = 0xbb40e64f;
    }
    else if ((DAT_10005000 & 0xffff0000) == 0) {
      DAT_10005000 = DAT_10005000 | DAT_10005000 << 0x10;
    }
  }
  DAT_10005004 = ~DAT_10005000;
  return;
}



// ===================== _crt_debugger_hook @ 1000366a =====================

void __cdecl _crt_debugger_hook(int param_1)

{
                    /* WARNING: Could not recover jumptable at 0x1000366a. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  _crt_debugger_hook(param_1);
  return;
}



// ===================== except_handler4_common @ 10003670 =====================

void __cdecl except_handler4_common(void)

{
                    /* WARNING: Could not recover jumptable at 0x10003670. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  except_handler4_common();
  return;
}



// ===================== memset @ 1000367c =====================

void * __cdecl memset(void *_Dst,int _Val,size_t _Size)

{
  void *pvVar1;
  
                    /* WARNING: Could not recover jumptable at 0x1000367c. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  pvVar1 = memset(_Dst,_Val,_Size);
  return pvVar1;
}




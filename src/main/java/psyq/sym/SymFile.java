package psyq.sym;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ghidra.app.cmd.comments.SetCommentCmd;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.store.LockException;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.address.AddressOverflowException;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.TypedefDataType;
import ghidra.program.model.data.Union;
import ghidra.program.model.data.UnionDataType;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryConflictException;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;
import psx.PsxLoader;

public class SymFile {
	private boolean isApplyStep2 = false;
	private Set<SymObject> types = new HashSet<>();
	private List<SymName> names = new ArrayList<>();
	private List<SymExtStat> namesWithTypes = new ArrayList<>();
	private Map<String, SymStructUnionEnum> fakeObjs = new HashMap<>();
	private Map<String, SymFunc> funcs = new HashMap<>();
	private List<SymOverlay> overlays = new ArrayList<>();
	
	public static SymFile fromBinary(String path, Program program, MessageLog log, TaskMonitor monitor) {
		try {
			FileInputStream fis = new FileInputStream(path);
			byte[] fileData = fis.readAllBytes();
			fis.close();
			
			ByteArrayProvider provider = new ByteArrayProvider(fileData);
			BinaryReader reader = new BinaryReader(provider, true);
			return new SymFile(reader, program, log, monitor);
		} catch (IOException e) {
			log.appendException(e);
			return null;
		}
	}

	private SymFile(BinaryReader reader, Program program, MessageLog log, TaskMonitor monitor) throws IOException {
		String sig = reader.readNextAsciiString(3);
		
		if (!sig.equals("MND")) {
			throw new IOException("Wrong MND signature");
		}
		
		reader.readNextUnsignedByte(); // version
		reader.readNextUnsignedByte(); // unit
		reader.readNextByteArray(3); // skip
		
		SymStructUnionEnum currStructUnion = null;
		SymFunc currFunc = null;
		long currOverlay = 0L;
		
		while (reader.getPointerIndex() < reader.length()) {
			long offset = 0;
			int tag = 0;

			while (true) {
				offset = reader.readNextUnsignedInt();
				tag = reader.readNextUnsignedByte();
				
				if (tag != 8) {
					break;
				}
				
				reader.readNextUnsignedByte(); // MX-info
			}
			
			if (tag <= 0x7F) {
				String name = readString(reader);
				SymName obj = new SymName(name, offset, currOverlay);
				names.add(obj);
				continue;
			}
			
			switch (tag) {
			case 0x80: {
			} break;
			case 0x82: {
				reader.readNextUnsignedByte(); // line byte_add
			} break;
			case 0x84: {
				reader.readNextUnsignedShort(); // line word_add
			} break;
			case 0x86: {
				reader.readNextUnsignedInt(); // new line_num
			} break;
			case 0x88: {
				reader.readNextUnsignedInt(); // new line_num
				readString(reader); // new line_num to file_name
			} break;
			case 0x8A: {
			} break;
			case 0x8C: 
			case 0x9C: {
				reader.readNextUnsignedShort(); // fp
				reader.readNextUnsignedInt(); // fsize
				reader.readNextUnsignedShort(); // retreg
				reader.readNextUnsignedInt(); // mask
				reader.readNextUnsignedInt(); // maskoffs
				
				if (tag == 0x9C) {
					reader.readNextUnsignedInt(); // fmask
					reader.readNextUnsignedInt(); // fmaskoffs
				}
				
				reader.readNextUnsignedInt(); // line
				String fileName = readString(reader);
				String funcName = readString(reader); // funcName
				
				SymFunc func = currFunc = funcs.get(funcName);
				
				if (func == null) {
					func = currFunc = new SymFunc(new SymDef(SymDefClass.EXT,
									              new SymDefType(new SymDefTypePrim[] {SymDefTypePrim.FCN, SymDefTypePrim.VOID}),
									                             false, 0L, funcName, offset, currOverlay),
							                      funcName,
							                      offset, currOverlay);
				}
				
				func.setFileName(fileName);

				funcs.put(funcName, func);
			} break;
			case 0x8E: {
				reader.readNextUnsignedInt(); // func end line
				if (currFunc == null) {
					throw new IOException("End of non-started function");
				}
				
				currFunc.setEndOffset(offset);
				currFunc = null;
			} break;
			case 0x90: {
				reader.readNextUnsignedInt(); // block start line
			} break;
			case 0x92: {
				reader.readNextUnsignedInt(); // block end line
			} break;
			case 0x94:
			case 0x96: {
				SymDefClass defClass = SymDefClass.fromInt(reader.readNextUnsignedShort());
				SymDefType defType = new SymDefType(reader.readNextUnsignedShort());
				long size = reader.readNextUnsignedInt();
				
				List<Integer> dims = null;
				String defTag = null;
				
				if (tag == 0x96) {
					int dimsCount = reader.readNextUnsignedShort();
					dims = new ArrayList<>();
					
					for (int i = 0; i < dimsCount; ++i) {
						dims.add((int)reader.readNextUnsignedInt());
					}
					
					defTag = readString(reader);
				}
				
				String defName = readString(reader);
				
				SymDef def2 = new SymDef(defClass, defType, tag == 0x96, size, defName, offset, currOverlay);
				
				if (tag == 0x96) {
					def2.setDims(dims.toArray(Integer[]::new));
					def2.setTag(defTag);
				}
				
				switch (defClass) {
				case ARG:
				case REGPARM: {
					if (currFunc == null) {
						throw new IOException("Parameter for non-started function");
					}
					
					if (fakeObjs.containsKey(defTag)) {
						SymStructUnionEnum obj = fakeObjs.get(defTag);
						def2.setTag(obj.getName());
					}

					currFunc.addArgument(def2);
				} break;
				case EXT:
				case STAT: {
					SymDefTypePrim[] typesList = defType.getTypesList();
					
					if (typesList.length >= 1 && typesList[0] == SymDefTypePrim.FCN) {
						SymFunc func = new SymFunc(def2, defName, offset, currOverlay);
						funcs.put(defName, func);
					} else if (currFunc == null) { // exclude function blocks
						if (fakeObjs.containsKey(defTag)) {
							SymStructUnionEnum obj = fakeObjs.get(defTag);
							def2.setTag(obj.getName());
						}
						
						namesWithTypes.add(new SymExtStat(def2, offset, currOverlay));
					}
				} break;
				case TPDEF: {
					if (fakeObjs.containsKey(defTag)) {
						SymStructUnionEnum obj = fakeObjs.get(defTag);
						obj.setName(defName);
						fakeObjs.replace(defTag, obj);
						types.add(obj);
					} else {
						types.add(new SymTypedef(def2));
					}
				} break;
				// STRUCT, UNION, ENUM begin
				case STRTAG:
				case UNTAG:
				case ENTAG: {
					currStructUnion = new SymStructUnionEnum(defName, size, defType.getPrimaryType());
				} break;
				// STRUCT, UNION, ENUM fields
				case MOS:
				case MOU:
				case MOE: {
					if (currStructUnion == null) {
						throw new IOException("Non-defined struct|union|enum field definition");
					}
					
					if (fakeObjs.containsKey(defTag)) {
						SymStructUnionEnum obj = fakeObjs.get(defTag);
						def2.setTag(obj.getName());
					}
					
					currStructUnion.addField(def2);
				} break;
				// STRUCT, UNION, ENUM end
				case EOS: {
					if (currStructUnion == null) {
						throw new IOException("End of non-defined struct|union|enum");
					}

					if (defType.getPrimaryType() != SymDefTypePrim.NULL || dims.size() != 0) {
						throw new IOException("Wrong EOS type");
					}
					
					if (defTag.matches("\\.\\d+fake")) {
						fakeObjs.put(defTag, currStructUnion);
					} else {
						types.add(currStructUnion);
					}

					currStructUnion = null;
				} break;
				default: break;
				}
			} break;
			case 0x98: {
				long ovrLength = reader.readNextUnsignedInt(); // ovr_length
				long ovrId = reader.readNextUnsignedInt(); // ovr_id
				overlays.add(new SymOverlay(offset, ovrId, ovrLength));
			} break;
			case 0x9A: {
				currOverlay = offset;
			} break;
			case 0x9E: {
				readString(reader); // mangled name1
				readString(reader); // mangled name2
			} break;
			}
		}
	}
	
	public void applyOverlays(Program program, MessageLog log, TaskMonitor monitor) {
		monitor.initialize(overlays.size());
		monitor.setMessage("Creating overlays...");
		monitor.clearCanceled();
		
		Memory mem = program.getMemory();
		AddressSpace defAddressSpace = program.getAddressFactory().getDefaultAddressSpace();
		
		for (int i = 0; i < overlays.size(); ++i) {
			if (monitor.isCancelled()) {
				break;
			}
			
			SymOverlay ovr = overlays.get(i);
			try {
				MemoryBlock block = mem.createUninitializedBlock(SymOverlay.getBlockName(ovr.getId()), defAddressSpace.getAddress(ovr.getOffset()), ovr.getSize(), true);
				block.setExecute(true);
				block.setRead(true);
				block.setWrite(true);
			} catch (LockException | DuplicateNameException | MemoryConflictException | AddressOverflowException | AddressOutOfBoundsException e) {
				log.appendException(e);
				return;
			}
			
			monitor.setProgress(i + 1);
		}
		
		monitor.setMessage("Overlays created.");
	}
	
	private void applyNames(Program program, MessageLog log, TaskMonitor monitor) {
		if (isApplyStep2) {
			return;
		}
		
		List<SymObject> objects = new ArrayList<>(names);
		
		monitor.initialize(objects.size());
		monitor.setMessage("Applying SYM objects (names)...");
		monitor.clearCanceled();
		
		applySymbols(objects, true, program, log, monitor);
	}
	
	private void applyTypes(boolean noFields, Program program, MessageLog log, TaskMonitor monitor) {
		List<SymObject> objects = new ArrayList<>(types);
		
		monitor.initialize(objects.size());
		monitor.setMessage(String.format("Applying SYM objects (types, Step: %d)...", isApplyStep2 ? 2 : 1));
		monitor.clearCanceled();
		
		applySymbols(objects, noFields, program, log, monitor);
	}
	
	private void applyNamesWithTypes(Program program, MessageLog log, TaskMonitor monitor) {
		List<SymObject> objects = new ArrayList<>(namesWithTypes);
		
		monitor.initialize(objects.size());
		monitor.setMessage(String.format("Applying SYM objects (typed names, Step: %d)...", isApplyStep2 ? 2 : 1));
		monitor.clearCanceled();
		
		applySymbols(objects, true, program, log, monitor);
	}
	
	private void applyFuncs(Program program, MessageLog log, TaskMonitor monitor) {
		List<SymObject> objects = new ArrayList<>(funcs.values());
		
		monitor.initialize(objects.size());
		monitor.setMessage(String.format("Applying SYM objects (functions, Step: %d)...", isApplyStep2 ? 2 : 1));
		monitor.clearCanceled();
		
		applySymbols(objects, true, program, log, monitor);
	}
	
	public void apply(boolean noFields, Program program, MessageLog log, TaskMonitor monitor) {
		applyNames(program, log, monitor);
		applyTypes(noFields, program, log, monitor);
		applyNamesWithTypes(program, log, monitor);
		applyFuncs(program, log, monitor);
		
		isApplyStep2 = !isApplyStep2;
	}
	
	private void applySymbols(final List<SymObject> objects, boolean noFields, Program program, MessageLog log, TaskMonitor monitor) {
		DataTypeManager mgr = program.getDataTypeManager();
		AddressFactory addrFact = program.getAddressFactory();
		
		int index = 0;
		
		Set<SymName> tryAgain = new HashSet<>();

		for (SymObject obj : objects) {
			if (monitor.isCancelled()) {
				break;
			}
			
			AddressSpace addrSpace = addrFact.getAddressSpace(SymOverlay.getBlockName(obj.getOverlayId()));
			
			if (addrSpace == null) {
				addrSpace = addrFact.getDefaultAddressSpace();
			}
			
			Address addr = addrSpace.getAddress(obj.getOffset());
			
			SymName rep = applySymbol(obj, addr, noFields, mgr, program, log);
			if (rep != null) {
				tryAgain.add(rep);
			}
			
			monitor.setProgress(index + 1);
			index++;
		}
		
		monitor.initialize(tryAgain.size());
		monitor.setMessage("Applying SYM forward usage objects...");
		monitor.clearCanceled();
		
		boolean repeat = true;

		List<SymName> tryAgainList = new ArrayList<>(tryAgain);
		while (repeat) {
			ListIterator<SymName> i = tryAgainList.listIterator();
			repeat = false;
			
			while (i.hasNext()) {
				if (monitor.isCancelled()) {
					break;
				}
				
				SymObject obj = i.next();
				
				AddressSpace addrSpace = addrFact.getAddressSpace(SymOverlay.getBlockName(obj.getOverlayId()));
				
				if (addrSpace == null) {
					addrSpace = addrFact.getDefaultAddressSpace();
				}
				
				Address addr = addrSpace.getAddress(obj.getOffset());
				
				SymName rep = applySymbol(obj, addr, noFields, mgr, program, log);
				if (rep == null) {
					i.remove();
					repeat = true;
					
					monitor.setProgress(tryAgainList.size());
				} else {
					if (!tryAgainList.contains(rep)) {
						i.add(rep);
					}
				}
			}
		}
		
		if (isApplyStep2) {
			dumpNotFound(tryAgainList, log);
		}
	}
	
	private static void dumpNotFound(final List<SymName> tryAgain, MessageLog log) {
		if (tryAgain.isEmpty()) {
			return;
		}
		
		Set<SymName> uniqueStructs = tryAgain.stream().filter(SymStructUnionEnum.class::isInstance).map(SymStructUnionEnum.class::cast).collect(Collectors.toSet());
		Set<SymName> uniqueDefs = tryAgain.stream().filter(SymExtStat.class::isInstance).map(SymExtStat.class::cast).collect(Collectors.toSet());
		Set<SymName> uniqueFuncs = tryAgain.stream().filter(SymFunc.class::isInstance).map(SymFunc.class::cast).collect(Collectors.toSet());
		Set<SymName> uniqueTpdefs = tryAgain.stream().filter(SymTypedef.class::isInstance).map(SymTypedef.class::cast).collect(Collectors.toSet());
		
		tryAgain.removeAll(uniqueStructs);
		tryAgain.removeAll(uniqueDefs);
		tryAgain.removeAll(uniqueFuncs);
		tryAgain.removeAll(uniqueTpdefs);
		Set<SymName> uniqueRest = new HashSet<>((tryAgain));
		
		boolean printed = false;
		for (SymName obj : uniqueStructs) {
			if (!printed) {
				log.appendMsg("The following structures haven't been found:");
				printed = true;
			}
			
			log.appendMsg(String.format("\t%s", obj.getName()));
		}
		
		printed = false;
		for (SymName obj : uniqueDefs) {
			if (!printed) {
				log.appendMsg("The following definitions haven't been found:");
				printed = true;
			}
			
			log.appendMsg(String.format("\tName: %s, Type: %s", obj.getName(), ((SymExtStat)obj).getTag()));
		}
		
		printed = false;
		for (SymName obj : uniqueFuncs) {
			if (!printed) {
				log.appendMsg("The following functions haven't been found:");
				printed = true;
			}
			
			log.appendMsg(String.format("\t%s", obj.getName()));
		}
		
		printed = false;
		for (SymName obj : uniqueTpdefs) {
			if (!printed) {
				log.appendMsg("The following typedefs haven't been found:");
				printed = true;
			}
			
			log.appendMsg(String.format("\t%s -> %s", ((SymTypedef)obj).getTag(), obj.getName()));
		}
		
		printed = false;
		for (SymName obj : uniqueRest) {
			if (!printed) {
				log.appendMsg("The following objects haven't been found:");
				printed = true;
			}
			
			log.appendMsg(String.format("\t%s (%s)", obj.getName(), obj.getClass().getName()));
		}
	}
	
	@SuppressWarnings("incomplete-switch")
	private static SymName applySymbol(SymObject obj, Address addr, boolean noFields, DataTypeManager mgr, Program program, MessageLog log) {
		SymbolTable st = program.getSymbolTable();
		
		if (obj instanceof SymFunc) {
			SymFunc sf = (SymFunc)obj;
			PsxLoader.setFunction(program, addr, sf.getName(), true, false, log);
			
			if (!setFunctionArguments(program, addr, sf, log)) {
				return sf;
			}
			
			SetCommentCmd cmd = new SetCommentCmd(addr, CodeUnit.PLATE_COMMENT, String.format("File: %s", sf.getFileName()));
			cmd.applyTo(program);
		} else if (obj instanceof SymTypedef) {
			SymTypedef tpdef = (SymTypedef)obj;

			DataType dt = tpdef.getBaseDataType(mgr);
			
			if (dt == null) {
				return new SymStructUnionEnum(tpdef.getTag(), 0L, type);
			}
			
			DataType baseType = new TypedefDataType(tpdef.getName(), dt);
			
			if (mgr.getDataType(baseType.getDataTypePath()) == null) {
				mgr.addDataType(baseType, DataTypeConflictHandler.REPLACE_HANDLER);
			}
		} else if (obj instanceof SymExtStat) {
			SymExtStat extStat = (SymExtStat)obj;
			
			DataType dt = extStat.getDataType(mgr);
			
			if (dt == null) {
				return extStat;
			}
			
			try {
				st.createLabel(addr, extStat.getName(), SourceType.ANALYSIS);
			} catch (InvalidInputException e) {
				log.appendException(e);
			}

			try {
				DataUtilities.createData(program, addr, dt, -1, false, ClearDataMode.CLEAR_ALL_CONFLICT_DATA);
			} catch (CodeUnitInsertionException e) {
				log.appendException(e);
			}
		} else if (obj instanceof SymStructUnionEnum) {
			SymStructUnionEnum ssu = (SymStructUnionEnum)obj;
			SymDefTypePrim type = ssu.getType();
			SymDef[] fields = ssu.getFields();
			
			switch (type) {
			case UNION: {
				UnionDataType udt = new UnionDataType(ssu.getName());
				udt.setMinimumAlignment(4);
				
				Union uut = (Union)mgr.addDataType(udt, DataTypeConflictHandler.REPLACE_HANDLER);
				
				if (!noFields) {
					for (SymDef field : fields) {
						DataType dt = field.getDataType(mgr);
						
						if (dt == null) {
							return new SymStructUnionEnum(field.getTag(), field.getSize(), field.getDefType().);
						}
						
						uut.add(dt, field.getName(), null);
					}
				}
			} break;
			case STRUCT: {
				StructureDataType sdt = new StructureDataType(ssu.getName(), 0);
				sdt.setMinimumAlignment(4);
				
				Structure ddt = (Structure)mgr.addDataType(sdt, DataTypeConflictHandler.REPLACE_HANDLER);
				
				if (!noFields) {
					for (SymDef field : fields) {
						DataType dt = field.getDataType(mgr);
						
						if (dt == null) {
							return new SymStructUnionEnum(field.getTag(), field.getSize(), type);
						}
						
						ddt.add(dt, field.getName(), null);
					}
				}
			} break;
			case ENUM: {
				EnumDataType edt = new EnumDataType(ssu.getName(), (int)ssu.getSize());
				
				for (SymDef field : fields) {
					edt.add(field.getName(), field.getOffset());
				}
				
				mgr.addDataType(edt, DataTypeConflictHandler.REPLACE_HANDLER);
			} break;
			}
		} else if (obj instanceof SymName) {
			SymName sn = (SymName)obj;
			try {
				st.createLabel(addr, sn.getName(), SourceType.ANALYSIS);
			} catch (InvalidInputException e) {
				if (!sn.getName().startsWith("MENU_")) {
					log.appendException(e);
				} else {
					try {
						st.createLabel(addr, String.format("_%s", sn.getName()), SourceType.ANALYSIS);
					} catch (InvalidInputException e1) {
						log.appendException(e);
					}
				}
			}
		} else {
			log.appendMsg(String.format("unkn type offset: 0x%08X", addr.getOffset()));
		}
		
		return null;
	}
	
	private static boolean setFunctionArguments(Program program, Address funcAddr, SymFunc funcDef, MessageLog log) {
		try {
			DataTypeManager mgr = program.getDataTypeManager();
			Function func = program.getListing().getFunctionAt(funcAddr);
			
			if (func == null) {
				log.appendMsg(String.format("Cannot get function at: 0x%08X", funcAddr.getOffset()));
				return true;
			}
			
			DataType dt = funcDef.getReturnType().getDataType(mgr);
			
			if (dt == null) {
				return false;
			}
			
			func.setReturnType(dt, SourceType.ANALYSIS);
			
			List<ParameterImpl> params = new ArrayList<>();
			SymDef[] args = funcDef.getArguments();
			for (int i = 0; i < args.length; ++i) {
				DataType argType = args[i].getDataType(mgr);
				
				if (argType == null) {
					return false;
				}
				
				params.add(new ParameterImpl(args[i].getName(), argType, program));
			}
			
			func.updateFunction("__stdcall", null, FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true, SourceType.ANALYSIS, params.toArray(ParameterImpl[]::new));
		} catch (Exception e) {
			log.appendException(e);
			return false;
		}
		
		return true;
	}
	
	private static String readString(BinaryReader reader) throws IOException {
		return reader.readNextAsciiString(reader.readNextUnsignedByte());
	}
}
